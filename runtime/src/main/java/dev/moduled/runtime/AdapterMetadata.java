package dev.moduled.runtime;

import android.content.Context;
import dev.moduled.api.HostInfo;
import dev.moduled.core.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Сведения о версионном адаптере точки входа (ТЗ §7.3 PAT-08/PAT-09).
 *
 * <p>Патчер кладёт в assets патченного APK файл {@value #ASSET_PATH}, где
 * зафиксированы: целевой package ID, версия TikTok, имя и версия адаптера и
 * подтверждение поддержки. Если файла нет, runtime честно сообщает
 * «адаптер неизвестен, поддержка не подтверждена» вместо предположения, что
 * перехват выполнен.</p>
 */
public final class AdapterMetadata {

    public static final String ASSET_PATH = "tmoduled/adapter.json";

    private AdapterMetadata() {
    }

    public static RuntimeHostInfo load(Context context) {
        String packageName = context.getPackageName();
        String versionName = "unknown";
        long versionCode = 0;
        try {
            android.content.pm.PackageInfo info =
                    context.getPackageManager().getPackageInfo(packageName, 0);
            if (info != null) {
                versionName = info.versionName != null ? info.versionName : "unknown";
                versionCode = info.getLongVersionCode();
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            // Сведения о версии не критичны для запуска: останутся unknown.
        }

        String[] abis = new String[0];
        try {
            abis = android.os.Build.SUPPORTED_ABIS != null
                    ? android.os.Build.SUPPORTED_ABIS : new String[0];
        } catch (RuntimeException ignored) {
            // Build.SUPPORTED_ABIS всегда доступен; защита от модифицированных прошивок.
        }

        byte[] asset = readAsset(context, ASSET_PATH);
        if (asset == null) {
            return new RuntimeHostInfo(packageName, versionName, versionCode,
                    "unknown", "unknown", false, abis);
        }
        try {
            Object parsed = JsonParser.parse(new String(asset, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) {
                return new RuntimeHostInfo(packageName, versionName, versionCode,
                        "invalid-adapter-metadata", "0", false, abis);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            String adapterName = asString(map.get("adapterName"), "unknown");
            String adapterVersion = asString(map.get("adapterVersion"), "0");
            Object supported = map.get("supported");
            boolean adapterSupported = Boolean.TRUE.equals(supported);
            String declaredPackage = asString(map.get("targetPackage"), packageName);
            if (!declaredPackage.equals(packageName)) {
                // Адаптер собран для другого package ID: поддержка не подтверждена.
                adapterSupported = false;
                adapterName = adapterName + "-package-mismatch";
            }
            return new RuntimeHostInfo(packageName, versionName, versionCode,
                    adapterName, adapterVersion, adapterSupported, abis);
        } catch (JsonParser.JsonException e) {
            return new RuntimeHostInfo(packageName, versionName, versionCode,
                    "invalid-adapter-metadata", "0", false, abis);
        }
    }

    private static String asString(Object value, String fallback) {
        return (value instanceof String) ? (String) value : fallback;
    }

    private static byte[] readAsset(Context context, String path) {
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > dev.moduled.core.Limits.MAX_DESCRIPTOR_BYTES) {
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Формирует содержимое asset'а — используется патчером. */
    public static String buildAssetJson(String targetPackage, String adapterName,
                                        String adapterVersion, boolean supported,
                                        String hostVersionName, long hostVersionCode) {
        return "{\"targetPackage\":\"" + targetPackage
                + "\",\"adapterName\":\"" + adapterName
                + "\",\"adapterVersion\":\"" + adapterVersion
                + "\",\"supported\":" + supported
                + ",\"hostVersionName\":\"" + hostVersionName
                + "\",\"hostVersionCode\":" + hostVersionCode + "}";
    }

    /** Проверяет, что asset описывает хоста (используется в тестах и патчером). */
    public static boolean isValidAsset(String json) {
        try {
            Object parsed = JsonParser.parse(json);
            return parsed instanceof Map;
        } catch (JsonParser.JsonException e) {
            return false;
        }
    }
}
