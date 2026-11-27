package dev.moduled.manager.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** §13.2: источники, кеш с датой обновления, изолированные ошибки источника. */
public class RepositoryStoreTest {

    private static final String CATALOG = "{\"schemaVersion\":1,\"name\":\"Каталог\","
            + "\"modules\":[{\"id\":\"org.author.example\",\"name\":\"Пример\","
            + "\"description\":\"описание\",\"manifestUrl\":\"https://author.example/m.json\"}]}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private RepositoryStore repositories;

    @Before
    public void setUp() throws IOException {
        repositories = new RepositoryStore(tmp.newFolder("repository"));
    }

    @Test
    public void addsAndRemovesHttpsSources() {
        assertTrue(repositories.addSource("https://catalog.example/index.json", "Основной").isOk());
        assertEquals(1, repositories.sources().size());
        assertEquals("Основной", repositories.sources().get(0).name);

        assertEquals("REPOSITORY_DUPLICATE",
                repositories.addSource("https://catalog.example/index.json", "Дубль").errorCode());
        assertEquals("URL_NOT_HTTPS",
                repositories.addSource("http://catalog.example/index.json", "HTTP").errorCode());
        assertEquals("URL_NOT_ALLOWED",
                repositories.addSource("https://localhost/index.json", "Локальный").errorCode());

        assertTrue(repositories.removeSource("https://catalog.example/index.json").isOk());
        assertTrue(repositories.sources().isEmpty());
        assertEquals("REPOSITORY_NOT_FOUND",
                repositories.removeSource("https://catalog.example/index.json").errorCode());
    }

    @Test
    public void cachesValidCatalogWithTimestamp() {
        String url = "https://catalog.example/index.json";
        assertTrue(repositories.addSource(url, null).isOk());
        assertEquals("catalog.example", repositories.sources().get(0).name);

        assertTrue(repositories.cacheCatalog(url, CATALOG.getBytes(StandardCharsets.UTF_8)).isOk());
        RepositoryStore.CachedCatalog cached = repositories.cachedCatalog(url).value();
        assertEquals("Каталог", cached.index.name);
        assertTrue(cached.updatedMs > 0);
        assertTrue(cached.ageMs(System.currentTimeMillis()) < 60_000);
    }

    /** Невалидный каталог не затирает последний валидный кеш. */
    @Test
    public void invalidCatalogDoesNotDestroyCache() {
        String url = "https://catalog.example/index.json";
        assertTrue(repositories.addSource(url, "Основной").isOk());
        assertTrue(repositories.cacheCatalog(url, CATALOG.getBytes(StandardCharsets.UTF_8)).isOk());

        assertEquals("CATALOG_BAD_JSON",
                repositories.cacheCatalog(url, "{oops".getBytes(StandardCharsets.UTF_8)).errorCode());
        assertTrue("Кеш обязан остаться валидным", repositories.cachedCatalog(url).isOk());
        assertEquals("Каталог", repositories.cachedCatalog(url).value().index.name);
    }

    @Test
    public void errorOfOneSourceDoesNotAffectOthers() {
        String first = "https://one.example/index.json";
        String second = "https://two.example/index.json";
        assertTrue(repositories.addSource(first, "Первый").isOk());
        assertTrue(repositories.addSource(second, "Второй").isOk());
        assertTrue(repositories.cacheCatalog(second, CATALOG.getBytes(StandardCharsets.UTF_8)).isOk());

        assertTrue(repositories.recordError(first, "timeout").isOk());
        List<RepositoryStore.Source> sources = repositories.sources();
        assertEquals("timeout", sources.get(0).lastError);
        assertEquals("", sources.get(1).lastError);
        assertTrue(repositories.cachedCatalog(second).isOk());
        assertEquals("CATALOG_NO_CACHE", repositories.cachedCatalog(first).errorCode());
    }

    @Test
    public void sourcesSurviveReload() {
        String url = "https://catalog.example/index.json";
        assertTrue(repositories.addSource(url, "Основной").isOk());
        assertTrue(repositories.setEnabled(url, false).isOk());
        assertTrue(repositories.cacheCatalog(url, CATALOG.getBytes(StandardCharsets.UTF_8)).isOk());

        RepositoryStore reloaded = new RepositoryStore(repositories.directoryForTest());
        assertEquals(1, reloaded.sources().size());
        assertFalse(reloaded.sources().get(0).enabled);
        assertTrue(reloaded.cachedCatalog(url).isOk());
    }

    @Test
    public void oversizedCatalogIsRejected() {
        String url = "https://catalog.example/index.json";
        assertTrue(repositories.addSource(url, "Основной").isOk());
        byte[] huge = new byte[3 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) 'a');
        assertEquals("CATALOG_TOO_LARGE", repositories.cacheCatalog(url, huge).errorCode());
        assertEquals("CATALOG_EMPTY", repositories.cacheCatalog(url, new byte[0]).errorCode());
    }
}
