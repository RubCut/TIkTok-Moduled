package dev.moduled.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Индекс статического каталога (ТЗ §13.1).
 *
 * <pre>
 * {"schemaVersion":1,"name":"...","modules":[
 *   {"id":"org.author.example","name":"...","description":"...",
 *    "manifestUrl":"https://author.example/module.json"}]}
 * </pre>
 *
 * <p>TModules читает машинные метаданные, а не HTML: веб-каталог — только
 * браузерное представление того же JSON.</p>
 */
public final class CatalogIndex {

    public static final int SCHEMA_VERSION = 1;

    public final String name;
    public final List<Entry> modules;

    public CatalogIndex(String name, List<Entry> modules) {
        this.name = name;
        this.modules = Collections.unmodifiableList(new ArrayList<>(modules));
    }

    public static final class Entry {
        public final String id;
        public final String name;
        public final String description;
        public final String manifestUrl;

        public Entry(String id, String name, String description, String manifestUrl) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.manifestUrl = manifestUrl;
        }
    }

    public static Result<CatalogIndex> validate(byte[] indexBytes) {
        if (indexBytes == null || indexBytes.length == 0) {
            return Result.fail("CATALOG_EMPTY", "Catalog index is empty");
        }
        if (indexBytes.length > Limits.MAX_CATALOG_BYTES) {
            return Result.fail("CATALOG_TOO_LARGE",
                    "Catalog index exceeds " + Limits.MAX_CATALOG_BYTES + " bytes");
        }
        Object parsed;
        try {
            parsed = JsonParser.parse(ManifestValidator.StrictUtf8.decode(indexBytes));
        } catch (IllegalArgumentException e) {
            return Result.fail("CATALOG_NOT_UTF8", "Catalog index is not valid UTF-8");
        } catch (JsonParser.JsonException e) {
            return Result.fail("CATALOG_BAD_JSON", "Catalog index is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            return Result.fail("CATALOG_NOT_OBJECT", "Catalog index must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        for (String key : map.keySet()) {
            switch (key) {
                case "schemaVersion":
                case "name":
                case "modules":
                case "updatedAt":
                    break;
                default:
                    return Result.fail("CATALOG_UNKNOWN_FIELD", "Unknown catalog field: " + key);
            }
        }
        Object schema = map.get("schemaVersion");
        if (!(schema instanceof Long) || ((Long) schema) != SCHEMA_VERSION) {
            return Result.fail("CATALOG_UNSUPPORTED_SCHEMA",
                    "Catalog schemaVersion " + schema + " is not supported (expected "
                            + SCHEMA_VERSION + ')');
        }
        Object name = map.get("name");
        if (!(name instanceof String) || ((String) name).isEmpty()) {
            return Result.fail("CATALOG_BAD_NAME", "Catalog 'name' must be a non-empty string");
        }
        Object modulesObj = map.get("modules");
        if (!(modulesObj instanceof List)) {
            return Result.fail("CATALOG_BAD_MODULES", "Catalog 'modules' must be an array");
        }
        List<Entry> entries = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object item : (List<?>) modulesObj) {
            if (!(item instanceof Map)) {
                return Result.fail("CATALOG_BAD_MODULES", "Catalog entry must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) item;
            for (String key : entry.keySet()) {
                switch (key) {
                    case "id":
                    case "name":
                    case "description":
                    case "manifestUrl":
                        break;
                    default:
                        return Result.fail("CATALOG_UNKNOWN_FIELD",
                                "Unknown catalog entry field: " + key);
                }
            }
            Object id = entry.get("id");
            if (!(id instanceof String) || !ModuleId.isValid((String) id)) {
                return Result.fail("CATALOG_BAD_ID", "Invalid module id in catalog: " + id);
            }
            if (!seen.add((String) id)) {
                // §13.2: повторный ID из разных источников требует выбора источника.
                return Result.fail("CATALOG_DUPLICATE_ID",
                        "Duplicate module id in catalog: " + id
                                + " (требуется явный выбор источника)");
            }
            Object entryName = entry.get("name");
            if (!(entryName instanceof String) || ((String) entryName).isEmpty()) {
                return Result.fail("CATALOG_BAD_NAME", "Catalog entry name must be non-empty");
            }
            Object description = entry.get("description");
            if (!(description instanceof String)) {
                return Result.fail("CATALOG_BAD_DESCRIPTION",
                        "Catalog entry description must be a string");
            }
            Object manifestUrl = entry.get("manifestUrl");
            if (!(manifestUrl instanceof String)
                    || !UrlPolicy.isAllowedHttps((String) manifestUrl)) {
                return Result.fail("CATALOG_BAD_URL",
                        "Catalog entry manifestUrl must be an allowed HTTPS URL: " + manifestUrl);
            }
            entries.add(new Entry((String) id, (String) entryName, (String) description,
                    (String) manifestUrl));
        }
        return Result.ok(new CatalogIndex((String) name, entries));
    }

    /** Поиск по названию, ID и описанию (§13.2). Регистр не учитывается. */
    public List<Entry> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return modules;
        }
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        List<Entry> found = new ArrayList<>();
        for (Entry entry : modules) {
            if (entry.id.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || entry.name.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || entry.description.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                found.add(entry);
            }
        }
        return found;
    }
}
