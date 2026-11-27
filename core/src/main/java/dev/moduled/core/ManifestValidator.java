package dev.moduled.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Валидация внутреннего {@code module.json} (ТЗ §10.1, §10.3 PKG-04):
 * корректный JSON, обязательные поля, типы, дубликаты ключей, версия API.
 * Неизвестные поля отклоняются, чтобы будущие расширения не интерпретировались
 * старым загрузчиком молча (ТЗ §9.2).
 */
public final class ManifestValidator {
    /** Версия контракта SDK, которую понимает этот загрузчик (ТЗ §9.2). */
    public static final int SUPPORTED_API_VERSION = 1;

    private ManifestValidator() {
    }

    public static Result<ModuleManifest> validate(byte[] manifestBytes) {
        if (manifestBytes == null || manifestBytes.length == 0) {
            return Result.fail("MANIFEST_EMPTY", "module.json is empty");
        }
        if (manifestBytes.length > Limits.MAX_DESCRIPTOR_BYTES) {
            return Result.fail("MANIFEST_TOO_LARGE",
                    "module.json exceeds " + Limits.MAX_DESCRIPTOR_BYTES + " bytes");
        }
        String text;
        try {
            text = StrictUtf8.decode(manifestBytes);
        } catch (IllegalArgumentException e) {
            return Result.fail("MANIFEST_NOT_UTF8", "module.json is not valid UTF-8: " + e.getMessage());
        }
        Object parsed;
        try {
            parsed = JsonParser.parse(text);
        } catch (JsonParser.JsonException e) {
            return Result.fail("MANIFEST_BAD_JSON", "module.json is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            return Result.fail("MANIFEST_NOT_OBJECT", "module.json must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;

        // Неизвестные поля — отказ (ТЗ §9.2): старое ядро не должно молча
        // интерпретировать поля будущих версий формата.
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
                    break;
                default:
                    return Result.fail("MANIFEST_UNKNOWN_FIELD", "Unknown field in module.json: " + key);
            }
        }

        String id = requireString(map, "id");
        if (id == null) {
            return Result.fail("MANIFEST_BAD_ID", "Field 'id' must be a non-empty string");
        }
        if (!ModuleId.isValid(id)) {
            return Result.fail("MANIFEST_BAD_ID", "Invalid module id: " + id);
        }

        String name = requireString(map, "name");
        if (name == null || name.isEmpty()) {
            return Result.fail("MANIFEST_BAD_NAME", "Field 'name' must be a non-empty string");
        }

        String description = requireString(map, "description");
        if (description == null) {
            return Result.fail("MANIFEST_BAD_DESCRIPTION", "Field 'description' must be a string");
        }

        String version = requireString(map, "version");
        if (version == null || version.isEmpty()) {
            return Result.fail("MANIFEST_BAD_VERSION", "Field 'version' must be a non-empty string");
        }

        Long versionCode = requireLong(map, "versionCode");
        if (versionCode == null || versionCode < 0) {
            return Result.fail("MANIFEST_BAD_VERSION_CODE",
                    "Field 'versionCode' must be a non-negative integer");
        }

        Long apiVersionLong = requireLong(map, "apiVersion");
        if (apiVersionLong == null || apiVersionLong < 1 || apiVersionLong > Integer.MAX_VALUE) {
            return Result.fail("MANIFEST_BAD_API_VERSION", "Field 'apiVersion' must be a positive integer");
        }
        int apiVersion = apiVersionLong.intValue();
        if (apiVersion != SUPPORTED_API_VERSION) {
            return Result.fail("MANIFEST_UNSUPPORTED_API",
                    "Module requires apiVersion " + apiVersion + ", loader supports " + SUPPORTED_API_VERSION);
        }

        String entrypoint = requireString(map, "entrypoint");
        if (entrypoint == null || !isClassName(entrypoint)) {
            return Result.fail("MANIFEST_BAD_ENTRYPOINT", "Field 'entrypoint' must be a fully qualified class name");
        }

        Object targetPackagesObj = map.get("targetPackages");
        if (!(targetPackagesObj instanceof List)) {
            return Result.fail("MANIFEST_BAD_TARGET_PACKAGES",
                    "Field 'targetPackages' must be an array of strings");
        }
        List<String> targetPackages = new ArrayList<>();
        for (Object item : (List<?>) targetPackagesObj) {
            if (!(item instanceof String) || ((String) item).isEmpty()) {
                return Result.fail("MANIFEST_BAD_TARGET_PACKAGES",
                        "Field 'targetPackages' must contain only non-empty strings");
            }
            targetPackages.add((String) item);
        }
        if (targetPackages.isEmpty()) {
            return Result.fail("MANIFEST_BAD_TARGET_PACKAGES",
                    "Field 'targetPackages' must not be empty");
        }

        return Result.ok(new ModuleManifest(id, name, description, version,
                versionCode, apiVersion, entrypoint, targetPackages));
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return (value instanceof String) ? (String) value : null;
    }

    private static Long requireLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return (value instanceof Long) ? (Long) value : null;
    }

    private static boolean isClassName(String name) {
        if (name.isEmpty() || name.length() > 512) {
            return false;
        }
        String[] parts = name.split("\\.");
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            char first = part.charAt(0);
            if (!(Character.isLetter(first) || first == '_' || first == '$')) {
                return false;
            }
            for (int i = 1; i < part.length(); i++) {
                char c = part.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$')) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Строгая UTF-8 декодировка: неверные последовательности отклоняются (CFG-04). */
    public static final class StrictUtf8 {
        private StrictUtf8() {
        }

        public static String decode(byte[] bytes) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
    }
}
