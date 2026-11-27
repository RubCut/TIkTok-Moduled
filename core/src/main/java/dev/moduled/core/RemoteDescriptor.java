package dev.moduled.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Авторский descriptor распространения (ТЗ §10.2).
 *
 * <p>Descriptor содержит поля внутреннего манифеста плюс транспортные сведения:
 * {@code downloadUrl}, {@code archiveSha256}, необязательные {@code iconUrl} и
 * {@code screenshots}, сведения об авторе и источнике.</p>
 *
 * <p>Важное правило: {@code archiveSha256} НЕ помещается внутрь того же ZIP как
 * хеш всего этого ZIP — это самоссылка. В архиве хранится копия манифеста
 * исполняемого модуля; внешний descriptor добавляет транспортные поля. Поэтому
 * валидатор внутреннего манифеста отклоняет транспортные поля как неизвестные,
 * а сравнение при скачивании идёт по общим значимым полям, а не побайтово.</p>
 */
public final class RemoteDescriptor {

    public final ModuleManifest manifest;
    public final String downloadUrl;
    public final String archiveSha256;
    public final String iconUrl;
    public final List<String> screenshots;
    public final String authorName;
    public final String authorUrl;
    public final String sourceUrl;
    /** Fingerprint авторского ключа, если подпись предусмотрена форматом источника. */
    public final String authorKeyFingerprint;
    public final String signature;

    public RemoteDescriptor(ModuleManifest manifest, String downloadUrl, String archiveSha256,
                            String iconUrl, List<String> screenshots, String authorName,
                            String authorUrl, String sourceUrl, String authorKeyFingerprint,
                            String signature) {
        this.manifest = manifest;
        this.downloadUrl = downloadUrl;
        this.archiveSha256 = archiveSha256;
        this.iconUrl = iconUrl;
        this.screenshots = Collections.unmodifiableList(
                screenshots != null ? new ArrayList<>(screenshots) : new ArrayList<>());
        this.authorName = authorName;
        this.authorUrl = authorUrl;
        this.sourceUrl = sourceUrl;
        this.authorKeyFingerprint = authorKeyFingerprint;
        this.signature = signature;
    }

    public static Result<RemoteDescriptor> validate(byte[] descriptorBytes) {
        if (descriptorBytes == null || descriptorBytes.length == 0) {
            return Result.fail("DESCRIPTOR_EMPTY", "Descriptor is empty");
        }
        if (descriptorBytes.length > Limits.MAX_DESCRIPTOR_BYTES) {
            return Result.fail("DESCRIPTOR_TOO_LARGE",
                    "Descriptor exceeds " + Limits.MAX_DESCRIPTOR_BYTES + " bytes");
        }
        String text;
        try {
            text = ManifestValidator.StrictUtf8.decode(descriptorBytes);
        } catch (IllegalArgumentException e) {
            return Result.fail("DESCRIPTOR_NOT_UTF8", "Descriptor is not valid UTF-8");
        }
        Object parsed;
        try {
            parsed = JsonParser.parse(text);
        } catch (JsonParser.JsonException e) {
            return Result.fail("DESCRIPTOR_BAD_JSON", "Descriptor is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            return Result.fail("DESCRIPTOR_NOT_OBJECT", "Descriptor must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        for (String key : map.keySet()) {
            switch (key) {
                case "id":
                case "name":
                case "description":
                case "version":
                case "versionCode":
                case "apiVersion":
                case "entrypoint":
                case "targetPackages":
                case "downloadUrl":
                case "archiveSha256":
                case "iconUrl":
                case "screenshots":
                case "authorName":
                case "authorUrl":
                case "sourceUrl":
                case "authorKeyFingerprint":
                case "signature":
                    break;
                default:
                    return Result.fail("DESCRIPTOR_UNKNOWN_FIELD",
                            "Unknown descriptor field: " + key
                                    + " (новые поля требуют новой версии схемы)");
            }
        }

        // Общие значимые поля проверяются тем же валидатором манифеста: единые
        // правила для id, version, apiVersion, entrypoint и targetPackages.
        Result<ModuleManifest> manifest = validateManifestPart(map);
        if (!manifest.isOk()) {
            return Result.fail(manifest.errorCode(), manifest.errorMessage());
        }

        String downloadUrl = requireHttps(map, "downloadUrl");
        if (downloadUrl == null) {
            return Result.fail("DESCRIPTOR_BAD_DOWNLOAD_URL",
                    "Field 'downloadUrl' must be a valid HTTPS URL");
        }
        Object hashObj = map.get("archiveSha256");
        if (!(hashObj instanceof String) || !Hashing.isSha256Hex((String) hashObj)) {
            return Result.fail("DESCRIPTOR_BAD_HASH",
                    "Field 'archiveSha256' must be a SHA-256 hex value of the exact ZIP bytes");
        }
        String iconUrl = optionalHttps(map, "iconUrl");
        if (iconUrl == null && map.containsKey("iconUrl") && map.get("iconUrl") != null) {
            return Result.fail("DESCRIPTOR_BAD_ICON_URL", "Field 'iconUrl' must be an HTTPS URL");
        }
        List<String> screenshots = new ArrayList<>();
        Object shotsObj = map.get("screenshots");
        if (shotsObj != null) {
            if (!(shotsObj instanceof List)) {
                return Result.fail("DESCRIPTOR_BAD_SCREENSHOTS",
                        "Field 'screenshots' must be an array of HTTPS URLs");
            }
            for (Object item : (List<?>) shotsObj) {
                if (!(item instanceof String) || !UrlPolicy.isAllowedHttps((String) item)) {
                    return Result.fail("DESCRIPTOR_BAD_SCREENSHOTS",
                            "Screenshot URL must be HTTPS: " + item);
                }
                screenshots.add((String) item);
            }
        }
        String authorName = optionalString(map, "authorName");
        String authorUrl = optionalHttps(map, "authorUrl");
        String sourceUrl = optionalHttps(map, "sourceUrl");
        Object fingerprint = map.get("authorKeyFingerprint");
        if (fingerprint != null && (!(fingerprint instanceof String)
                || !Hashing.isSha256Hex((String) fingerprint))) {
            return Result.fail("DESCRIPTOR_BAD_FINGERPRINT",
                    "Field 'authorKeyFingerprint' must be a SHA-256 hex value");
        }
        Object signature = map.get("signature");
        if (signature != null && !(signature instanceof String)) {
            return Result.fail("DESCRIPTOR_BAD_SIGNATURE", "Field 'signature' must be a string");
        }
        return Result.ok(new RemoteDescriptor(manifest.value(), downloadUrl,
                (String) hashObj, iconUrl, screenshots, authorName, authorUrl, sourceUrl,
                fingerprint != null ? (String) fingerprint : null,
                signature != null ? (String) signature : null));
    }

    private static Result<ModuleManifest> validateManifestPart(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : new String[] {"id", "name", "description", "version", "versionCode",
                "apiVersion", "entrypoint", "targetPackages"}) {
            Object value = map.get(key);
            if (value == null) {
                return Result.fail("DESCRIPTOR_MISSING_FIELD",
                        "Descriptor must include manifest field: " + key);
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(JsonWriter.writeKey(key)).append(':').append(JsonWriter.writeValue(value));
        }
        sb.append('}');
        Result<ModuleManifest> result = ManifestValidator.validate(
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!result.isOk()) {
            return Result.fail(result.errorCode().replace("MANIFEST_", "DESCRIPTOR_"),
                    result.errorMessage());
        }
        return result;
    }

    private static String requireHttps(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String)) {
            return null;
        }
        return UrlPolicy.isAllowedHttps((String) value) ? (String) value : null;
    }

    private static String optionalHttps(Map<String, Object> map, String key) {
        return requireHttps(map, key);
    }

    private static String optionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return (value instanceof String) ? (String) value : null;
    }

    /**
     * Сравнение общих значимых полей descriptor и манифеста из архива (§10.2):
     * транспортные поля естественно различаются, поэтому побайтовое равенство
     * JSON не применяется.
     */
    public static Result<Void> matchesArchiveManifest(RemoteDescriptor descriptor,
                                                      ModuleManifest archiveManifest) {
        ModuleManifest expected = descriptor.manifest;
        List<String> mismatch = new ArrayList<>();
        if (!expected.id.equals(archiveManifest.id)) {
            mismatch.add("id");
        }
        if (!expected.name.equals(archiveManifest.name)) {
            mismatch.add("name");
        }
        if (!expected.description.equals(archiveManifest.description)) {
            mismatch.add("description");
        }
        if (!expected.version.equals(archiveManifest.version)) {
            mismatch.add("version");
        }
        if (expected.versionCode != archiveManifest.versionCode) {
            mismatch.add("versionCode");
        }
        if (expected.apiVersion != archiveManifest.apiVersion) {
            mismatch.add("apiVersion");
        }
        if (!expected.entrypoint.equals(archiveManifest.entrypoint)) {
            mismatch.add("entrypoint");
        }
        if (!expected.targetPackages.equals(archiveManifest.targetPackages)) {
            mismatch.add("targetPackages");
        }
        if (!mismatch.isEmpty()) {
            return Result.fail("DESCRIPTOR_MISMATCH",
                    "Descriptor and archive manifest differ in: " + mismatch);
        }
        return Result.ok(null);
    }
}
