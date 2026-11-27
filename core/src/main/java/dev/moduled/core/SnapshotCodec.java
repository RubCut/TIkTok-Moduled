package dev.moduled.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Сериализация и строгая проверка снимка модулей (ТЗ §20.2).
 *
 * <p>Формат v1: {@code {"schemaVersion":1,"generation":N,"createdWallMs":T,
 * "modules":[{"id","versionCode","dexSha256","configSha256","enabled"}]}}.
 * Декодирование отклоняет неизвестные поля, дубли ключей, неверные типы и
 * невалидные хеши, поэтому частично проверенный снимок не может быть опубликован
 * или прочитан как целый.</p>
 */
public final class SnapshotCodec {

    public static final int SCHEMA_VERSION = 1;

    private SnapshotCodec() {
    }

    public static String encode(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"schemaVersion\":").append(SCHEMA_VERSION)
                .append(",\"generation\":").append(snapshot.generation)
                .append(",\"createdWallMs\":").append(snapshot.createdWallMs)
                .append(",\"modules\":[");
        boolean first = true;
        for (Snapshot.ModuleEntry entry : snapshot.modules) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(quote(entry.id))
                    .append(",\"versionCode\":").append(entry.versionCode)
                    .append(",\"dexSha256\":").append(quote(entry.dexSha256))
                    .append(",\"configSha256\":").append(quote(entry.configSha256))
                    .append(",\"enabled\":").append(entry.enabled)
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public static Result<Snapshot> decode(String json) {
        if (json == null || json.isEmpty()) {
            return Result.fail("SNAPSHOT_EMPTY", "Snapshot is empty");
        }
        Object parsed;
        try {
            parsed = JsonParser.parse(json);
        } catch (JsonParser.JsonException e) {
            return Result.fail("SNAPSHOT_BAD_JSON", "Snapshot is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            return Result.fail("SNAPSHOT_NOT_OBJECT", "Snapshot must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed;

        for (String key : root.keySet()) {
            switch (key) {
                case "schemaVersion":
                case "generation":
                case "createdWallMs":
                case "modules":
                    break;
                default:
                    return Result.fail("SNAPSHOT_UNKNOWN_FIELD", "Unknown snapshot field: " + key);
            }
        }

        Long schema = asLong(root.get("schemaVersion"));
        if (schema == null || schema != SCHEMA_VERSION) {
            return Result.fail("SNAPSHOT_UNSUPPORTED_SCHEMA",
                    "Snapshot schemaVersion " + schema + " is not supported (expected "
                            + SCHEMA_VERSION + ")");
        }
        Long generation = asLong(root.get("generation"));
        if (generation == null || generation <= 0) {
            return Result.fail("SNAPSHOT_BAD_GENERATION",
                    "Snapshot generation must be a positive integer");
        }
        Long createdWallMs = asLong(root.get("createdWallMs"));
        if (createdWallMs == null || createdWallMs < 0) {
            return Result.fail("SNAPSHOT_BAD_TIMESTAMP", "Snapshot createdWallMs must be >= 0");
        }
        Object modulesObj = root.get("modules");
        if (!(modulesObj instanceof List)) {
            return Result.fail("SNAPSHOT_BAD_MODULES", "Snapshot 'modules' must be an array");
        }

        List<Snapshot.ModuleEntry> entries = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (Object item : (List<?>) modulesObj) {
            if (!(item instanceof Map)) {
                return Result.fail("SNAPSHOT_BAD_MODULES", "Snapshot module entry must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) item;
            for (String key : entry.keySet()) {
                switch (key) {
                    case "id":
                    case "versionCode":
                    case "dexSha256":
                    case "configSha256":
                    case "enabled":
                        break;
                    default:
                        return Result.fail("SNAPSHOT_UNKNOWN_FIELD",
                                "Unknown snapshot module field: " + key);
                }
            }
            Object idObj = entry.get("id");
            if (!(idObj instanceof String) || !ModuleId.isValid((String) idObj)) {
                return Result.fail("SNAPSHOT_BAD_MODULE_ID", "Invalid module id in snapshot: " + idObj);
            }
            String id = (String) idObj;
            if (!seenIds.add(id)) {
                return Result.fail("SNAPSHOT_DUPLICATE_MODULE",
                        "Duplicate module id in snapshot: " + id);
            }
            Long versionCode = asLong(entry.get("versionCode"));
            if (versionCode == null || versionCode < 0) {
                return Result.fail("SNAPSHOT_BAD_VERSION_CODE",
                        "Module " + id + " has invalid versionCode");
            }
            Object dexHash = entry.get("dexSha256");
            if (!(dexHash instanceof String) || !Hashing.isSha256Hex((String) dexHash)) {
                return Result.fail("SNAPSHOT_BAD_HASH",
                        "Module " + id + " has invalid dexSha256");
            }
            // configSha256 может быть строкой из 64 нулей, если конфига нет,
            // но обязан оставаться валидным SHA-256 значением.
            Object configHash = entry.get("configSha256");
            if (!(configHash instanceof String) || !Hashing.isSha256Hex((String) configHash)) {
                return Result.fail("SNAPSHOT_BAD_HASH",
                        "Module " + id + " has invalid configSha256");
            }
            Object enabled = entry.get("enabled");
            if (!(enabled instanceof Boolean)) {
                return Result.fail("SNAPSHOT_BAD_ENABLED",
                        "Module " + id + " has non-boolean 'enabled'");
            }
            entries.add(new Snapshot.ModuleEntry(id, versionCode, (String) dexHash,
                    (String) configHash, (Boolean) enabled));
        }
        return Result.ok(new Snapshot(generation, createdWallMs, entries));
    }

    private static Long asLong(Object value) {
        return (value instanceof Long) ? (Long) value : null;
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
