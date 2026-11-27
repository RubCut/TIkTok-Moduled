package dev.moduled.core;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Политика URL для каталога и репозиториев (ТЗ §13.2):
 * запрет HTTP downgrade, только HTTPS, отсутствие credentials в URL,
 * ограничение длины. Перенаправления перепроверяются этой же политикой.
 */
public final class UrlPolicy {

    /** Максимальная длина URL в descriptor и каталоге. */
    public static final int MAX_URL_LENGTH = 2048;

    /** Максимальное число перенаправлений, которое клиент обязан перепроверить. */
    public static final int MAX_REDIRECTS = 5;

    private UrlPolicy() {
    }

    public static boolean isAllowedHttps(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        // Credentials в URL запрещены: они попадают в логи и кеш.
        if (uri.getUserInfo() != null) {
            return false;
        }
        // Локальные адреса не являются допустимым источником модулей.
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("localhost") || lower.startsWith("127.")
                || lower.startsWith("10.") || lower.startsWith("192.168.")
                || lower.startsWith("169.254.") || lower.equals("[::1]")
                || lower.startsWith("172.16.") || lower.startsWith("172.17.")
                || lower.startsWith("fd") || lower.startsWith("fc")) {
            return false;
        }
        return uri.getFragment() == null;
    }

    /** Объяснение отказа — для экрана каталога и логов. */
    public static Result<Void> check(String url) {
        if (url == null || url.isEmpty()) {
            return Result.fail("URL_EMPTY", "URL is empty");
        }
        if (url.length() > MAX_URL_LENGTH) {
            return Result.fail("URL_TOO_LONG", "URL exceeds " + MAX_URL_LENGTH + " characters");
        }
        if (url.regionMatches(true, 0, "http://", 0, 7)) {
            return Result.fail("URL_NOT_HTTPS",
                    "HTTP запрещён: источник модулей обязан быть HTTPS (нет downgrade)");
        }
        if (!isAllowedHttps(url)) {
            return Result.fail("URL_NOT_ALLOWED",
                    "URL не соответствует политике: требуется HTTPS без учётных данных, "
                            + "не локальный адрес: " + url);
        }
        return Result.ok(null);
    }
}
