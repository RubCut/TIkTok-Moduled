package dev.moduled.manager.storage;

import dev.moduled.core.JsonParser;
import dev.moduled.core.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Состояние установленного модуля: включён ли он, откуда установлен, каким
 * ключом подтверждён и какое поколение снимка применено (ТЗ §11, §14.2, PKG-06).
 */
public final class ModuleState {

    /** Источник установки — для экрана доверия и карточки модуля. */
    public enum Origin {
        /** Локальный файл, выбранный пользователем. */
        LOCAL_FILE,
        /** Репозиторий из каталога. */
        REPOSITORY,
        /** Комплектный модуль поставки (регион, внутренний менеджер). */
        BUNDLED
    }

    public boolean enabled;
    public long installedAtMs;
    public long updatedAtMs;
    public Origin origin = Origin.LOCAL_FILE;
    public String sourceUrl = "";
    public String archiveSha256 = "";
    public String authorKeyFingerprint = "";
    /** Подтверждено ли пользователем доверие к этому коду (§14.2). */
    public boolean trustConfirmed;
    /** Поколение снимка, в которое модуль попал последний раз. */
    public long appliedGeneration;
    /** Комплектный модуль отображается с отметкой «комплектный» (§11). */
    public boolean bundled;

    public ModuleState copy() {
        ModuleState copy = new ModuleState();
        copy.enabled = enabled;
        copy.installedAtMs = installedAtMs;
        copy.updatedAtMs = updatedAtMs;
        copy.origin = origin;
        copy.sourceUrl = sourceUrl;
        copy.archiveSha256 = archiveSha256;
        copy.authorKeyFingerprint = authorKeyFingerprint;
        copy.trustConfirmed = trustConfirmed;
        copy.appliedGeneration = appliedGeneration;
        copy.bundled = bundled;
        return copy;
    }

    public String encode() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", 1L);
        map.put("enabled", enabled);
        map.put("installedAtMs", installedAtMs);
        map.put("updatedAtMs", updatedAtMs);
        map.put("origin", origin.name());
        map.put("sourceUrl", sourceUrl);
        map.put("archiveSha256", archiveSha256);
        map.put("authorKeyFingerprint", authorKeyFingerprint);
        map.put("trustConfirmed", trustConfirmed);
        map.put("appliedGeneration", appliedGeneration);
        map.put("bundled", bundled);
        return JsonWriter.writeValue(map);
    }

    public static ModuleState decode(String json) {
        ModuleState state = new ModuleState();
        if (json == null || json.isEmpty()) {
            return state;
        }
        Object parsed = JsonParser.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("state must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        state.enabled = Boolean.TRUE.equals(map.get("enabled"));
        state.installedAtMs = asLong(map.get("installedAtMs"), 0);
        state.updatedAtMs = asLong(map.get("updatedAtMs"), 0);
        state.origin = parseOrigin(asString(map.get("origin"), Origin.LOCAL_FILE.name()));
        state.sourceUrl = asString(map.get("sourceUrl"), "");
        state.archiveSha256 = asString(map.get("archiveSha256"), "");
        state.authorKeyFingerprint = asString(map.get("authorKeyFingerprint"), "");
        state.trustConfirmed = Boolean.TRUE.equals(map.get("trustConfirmed"));
        state.appliedGeneration = asLong(map.get("appliedGeneration"), 0);
        state.bundled = Boolean.TRUE.equals(map.get("bundled"));
        return state;
    }

    public static byte[] encodeBytes(ModuleState state) {
        return state.encode().getBytes(StandardCharsets.UTF_8);
    }

    private static Origin parseOrigin(String value) {
        try {
            return Origin.valueOf(value);
        } catch (IllegalArgumentException e) {
            return Origin.LOCAL_FILE;
        }
    }

    private static long asLong(Object value, long fallback) {
        return (value instanceof Long) ? (Long) value : fallback;
    }

    private static String asString(Object value, String fallback) {
        return (value instanceof String) ? (String) value : fallback;
    }
}
