package dev.moduled.manager.repo;

import dev.moduled.core.Limits;
import dev.moduled.core.Result;
import dev.moduled.core.UrlPolicy;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Реальный транспорт на {@link HttpURLConnection} (ТЗ §13.2).
 *
 * <ul>
 *   <li>HTTP downgrade запрещён: каждый URL и каждое перенаправление
 *       перепроверяются {@link UrlPolicy}.</li>
 *   <li>Перенаправления не обрабатываются автоматически — они обрабатываются вручную, чтобы
 *       политика применялась к каждому переходу.</li>
 *   <li>Размер ограничивается по фактическому потоку байтов.</li>
 *   <li>Отмена пользователем прерывает чтение.</li>
 * </ul>
 */
public final class UrlFetcher implements HttpFetcher {

    private static final String[] TEXT_TYPES = {"application/json", "text/"};

    @Override
    public byte[] fetch(String url, long maxBytes, int timeoutMs, int maxRedirects,
                        boolean[] cancel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long total = copy(url, out, maxBytes, timeoutMs, maxRedirects, cancel, false);
        if (total < 0) {
            throw new IOException("Download cancelled");
        }
        return out.toByteArray();
    }

    @Override
    public long fetchToFile(String url, File target, long maxBytes, int timeoutMs,
                            int maxRedirects, boolean[] cancel) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory for download: " + parent);
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            long total = copy(url, out, maxBytes, timeoutMs, maxRedirects, cancel, true);
            out.getFD().sync();
            if (total < 0) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                throw new IOException("Download cancelled");
            }
            return total;
        }
    }

    private long copy(String url, java.io.OutputStream out, long maxBytes, int timeoutMs,
                      int maxRedirects, boolean[] cancel, boolean flushToFile)
            throws IOException {
        String current = url;
        Result<Void> policy = UrlPolicy.check(current);
        if (!policy.isOk()) {
            throw new IOException(policy.errorCode() + ": " + policy.errorMessage());
        }
        long total = 0;
        for (int redirect = 0; redirect <= maxRedirects; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setRequestProperty("Accept", "application/json, */*");
                connection.setRequestProperty("User-Agent", "TModules/0.1");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IOException("Redirect without Location header from " + current);
                    }
                    URL resolved = new URL(new URL(current), location);
                    current = resolved.toString();
                    // Перенаправление перепроверяется той же политикой (§13.2).
                    Result<Void> redirected = UrlPolicy.check(current);
                    if (!redirected.isOk()) {
                        throw new IOException(redirected.errorCode() + ": "
                                + redirected.errorMessage() + " (redirect target: " + current + ')');
                    }
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + status + " for " + current);
                }
                String contentType = connection.getContentType();
                if (!flushToFile && contentType != null && !isExpectedContent(contentType)) {
                    throw new IOException("Unexpected Content-Type '" + contentType
                            + "' for " + current);
                }
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancel != null && cancel.length > 0 && cancel[0]) {
                            return -1;
                        }
                        total += read;
                        if (total > maxBytes) {
                            throw new IOException("Response exceeds limit of " + maxBytes
                                    + " bytes: " + current);
                        }
                        out.write(buffer, 0, read);
                    }
                }
                return total;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects (limit " + maxRedirects + ") for " + url);
    }

    private static boolean isExpectedContent(String contentType) {
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        for (String expected : TEXT_TYPES) {
            if (lower.contains(expected)) {
                return true;
            }
        }
        // Некоторые CDN отдают/octet-stream для JSON: размер и валидация всё равно
        // проверяются, поэтому жёсткий отказ здесь был бы ложной блокировкой.
        return lower.contains("octet-stream");
    }

    /** Лимиты по умолчанию для каталога и архивов. */
    public static long catalogLimit() {
        return Limits.MAX_CATALOG_BYTES;
    }

    public static long descriptorLimit() {
        return Limits.MAX_DESCRIPTOR_BYTES;
    }

    public static long archiveLimit() {
        return Limits.MAX_DOWNLOAD_BYTES;
    }
}
