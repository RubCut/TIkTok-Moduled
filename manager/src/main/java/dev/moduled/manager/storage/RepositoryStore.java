package dev.moduled.manager.storage;

import dev.moduled.core.AtomicFile;
import dev.moduled.core.CatalogIndex;
import dev.moduled.core.JsonParser;
import dev.moduled.core.JsonWriter;
import dev.moduled.core.Limits;
import dev.moduled.core.Result;
import dev.moduled.core.UrlPolicy;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Источники каталога и локальный кеш (ТЗ §13.1, §13.2).
 *
 * <p>Кеш последнего валидного каталога хранится с датой обновления. Ошибка одного
 * источника не уничтожает кеш и установленные модули: источник помечается ошибкой,
 * остальные продолжают работать.</p>
 */
public final class RepositoryStore {

    /** Источник каталога. */
    public static final class Source {
        public final String url;
        public String name;
        public boolean enabled;
        public long lastUpdatedMs;
        public String lastError = "";

        public Source(String url, String name, boolean enabled) {
            this.url = url;
            this.name = name;
            this.enabled = enabled;
        }
    }

    private final Path directory;
    private final List<Source> sources = new ArrayList<>();

    public RepositoryStore(File directory) {
        this.directory = directory.toPath();
        loadSources();
    }

    public List<Source> sources() {
        return new ArrayList<>(sources);
    }

    /** Каталог хранилища источников: нужен тестам и экспорту диагностики. */
    public File directoryForTest() {
        return directory.toFile();
    }

    public Result<Void> addSource(String url, String name) {
        Result<Void> policy = UrlPolicy.check(url);
        if (!policy.isOk()) {
            return policy;
        }
        for (Source source : sources) {
            if (source.url.equals(url)) {
                return Result.fail("REPOSITORY_DUPLICATE", "Источник уже добавлен: " + url);
            }
        }
        String effectiveName = name != null && !name.isEmpty() ? name : hostOf(url);
        sources.add(new Source(url, effectiveName, true));
        return flushSources();
    }

    public Result<Void> removeSource(String url) {
        boolean removed = sources.removeIf(source -> source.url.equals(url));
        if (!removed) {
            return Result.fail("REPOSITORY_NOT_FOUND", "Источник не найден: " + url);
        }
        deleteCache(url);
        return flushSources();
    }

    public Result<Void> setEnabled(String url, boolean enabled) {
        for (Source source : sources) {
            if (source.url.equals(url)) {
                source.enabled = enabled;
                return flushSources();
            }
        }
        return Result.fail("REPOSITORY_NOT_FOUND", "Источник не найден: " + url);
    }

    /** Сохраняет валидный каталог источника в кеш вместе с датой обновления. */
    public Result<Void> cacheCatalog(String url, byte[] catalogJson) {
        Source source = find(url);
        if (source == null) {
            return Result.fail("REPOSITORY_NOT_FOUND", "Источник не найден: " + url);
        }
        if (catalogJson == null || catalogJson.length == 0) {
            return Result.fail("CATALOG_EMPTY", "Пустой каталог не кешируется");
        }
        if (catalogJson.length > Limits.MAX_CATALOG_BYTES) {
            return Result.fail("CATALOG_TOO_LARGE",
                    "Каталог больше " + Limits.MAX_CATALOG_BYTES + " байт");
        }
        Result<CatalogIndex> validated = CatalogIndex.validate(catalogJson);
        if (!validated.isOk()) {
            // Невалидный каталог не затирает последний валидный кеш (§13.2).
            return Result.fail(validated.errorCode(), validated.errorMessage());
        }
        Result<Void> written = AtomicFile.write(cacheFile(url), catalogJson);
        if (!written.isOk()) {
            return written;
        }
        source.lastUpdatedMs = System.currentTimeMillis();
        source.lastError = "";
        return flushSources();
    }

    public Result<Void> recordError(String url, String message) {
        Source source = find(url);
        if (source == null) {
            return Result.fail("REPOSITORY_NOT_FOUND", "Источник не найден: " + url);
        }
        source.lastError = message != null ? message : "неизвестная ошибка";
        return flushSources();
    }

    /** Кеш последнего валидного каталога и его возраст. */
    public Result<CachedCatalog> cachedCatalog(String url) {
        Path cache = cacheFile(url);
        if (!Files.isRegularFile(cache)) {
            return Result.fail("CATALOG_NO_CACHE", "Кеш каталога отсутствует для " + url);
        }
        try {
            byte[] data = Files.readAllBytes(cache);
            Result<CatalogIndex> index = CatalogIndex.validate(data);
            if (!index.isOk()) {
                return Result.fail(index.errorCode(),
                        "Кеш каталога повреждён: " + index.errorMessage());
            }
            Source source = find(url);
            long updated = source != null ? source.lastUpdatedMs : Files.getLastModifiedTime(cache).toMillis();
            return Result.ok(new CachedCatalog(index.value(), updated));
        } catch (IOException e) {
            return Result.fail("CATALOG_UNREADABLE", "Кеш не читается: " + e.getMessage());
        }
    }

    /** Результат чтения кеша: индекс и дата обновления (§13.2). */
    public static final class CachedCatalog {
        public final CatalogIndex index;
        public final long updatedMs;

        public CachedCatalog(CatalogIndex index, long updatedMs) {
            this.index = index;
            this.updatedMs = updatedMs;
        }

        public long ageMs(long nowMs) {
            return Math.max(0, nowMs - updatedMs);
        }
    }

    private Source find(String url) {
        for (Source source : sources) {
            if (source.url.equals(url)) {
                return source;
            }
        }
        return null;
    }

    private Path cacheFile(String url) {
        String key = Integer.toHexString(url.hashCode()) + '-'
                + url.replaceAll("[^A-Za-z0-9._-]", "_");
        if (key.length() > 120) {
            key = key.substring(0, 120);
        }
        return directory.resolve("cache").resolve(key + ".json");
    }

    private void deleteCache(String url) {
        try {
            Files.deleteIfExists(cacheFile(url));
        } catch (IOException ignored) {
            // Кеш не критичен.
        }
    }

    private Path sourcesFile() {
        return directory.resolve("sources.json");
    }

    private void loadSources() {
        sources.clear();
        Path file = sourcesFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Object parsed = JsonParser.parse(json);
            if (!(parsed instanceof List)) {
                return;
            }
            for (Object item : (List<?>) parsed) {
                if (!(item instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) item;
                Object url = map.get("url");
                if (!(url instanceof String) || !UrlPolicy.isAllowedHttps((String) url)) {
                    continue;
                }
                Source source = new Source((String) url,
                        map.get("name") instanceof String ? (String) map.get("name") : hostOf((String) url),
                        !Boolean.FALSE.equals(map.get("enabled")));
                source.lastUpdatedMs = map.get("lastUpdatedMs") instanceof Long
                        ? (Long) map.get("lastUpdatedMs") : 0L;
                source.lastError = map.get("lastError") instanceof String
                        ? (String) map.get("lastError") : "";
                sources.add(source);
            }
        } catch (IOException | RuntimeException e) {
            // Повреждённый список источников читается как пустой.
            sources.clear();
        }
    }

    private Result<Void> flushSources() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Source source : sources) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("url", source.url);
            map.put("name", source.name);
            map.put("enabled", source.enabled);
            map.put("lastUpdatedMs", source.lastUpdatedMs);
            map.put("lastError", source.lastError);
            list.add(map);
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            return Result.fail("REPOSITORY_DIR_FAILED", e.getMessage());
        }
        return AtomicFile.write(sourcesFile(),
                JsonWriter.writeValue(list).getBytes(StandardCharsets.UTF_8));
    }

    private static String hostOf(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            return host != null ? host : url;
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return url;
        }
    }
}
