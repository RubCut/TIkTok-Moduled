package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/** §13.1: схема каталога; §13.2: поиск и конфликты ID. */
public class CatalogIndexTest {

    private static final String VALID = "{\"schemaVersion\":1,"
            + "\"name\":\"TikTok Moduled Modules\","
            + "\"modules\":["
            + "{\"id\":\"org.author.example\",\"name\":\"Пример модуля\","
            + "\"description\":\"Краткое описание\",\"manifestUrl\":\"https://author.example/module.json\"},"
            + "{\"id\":\"dev.moduled.region\",\"name\":\"Регион\","
            + "\"description\":\"Комплектный модуль региона\",\"manifestUrl\":\"https://moduled.dev/region.json\"}"
            + "]}";

    private static Result<CatalogIndex> parse(String json) {
        return CatalogIndex.validate(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void acceptsDocumentedSchema() {
        Result<CatalogIndex> result = parse(VALID);
        assertTrue(result.errorMessage(), result.isOk());
        assertEquals("TikTok Moduled Modules", result.value().name);
        assertEquals(2, result.value().modules.size());
        assertEquals("org.author.example", result.value().modules.get(0).id);
        assertEquals("https://moduled.dev/region.json", result.value().modules.get(1).manifestUrl);
    }

    @Test
    public void searchesByNameIdAndDescription() {
        CatalogIndex index = parse(VALID).value();
        assertEquals(1, index.search("регион").size());
        assertEquals("dev.moduled.region", index.search("dev.moduled.region").get(0).id);
        assertEquals(1, index.search("Краткое").size());
        assertEquals(2, index.search("  ").size());
        assertEquals(2, index.search(null).size());
        assertTrue(index.search("нет-такого").isEmpty());
    }

    @Test
    public void rejectsUnsupportedSchema() {
        assertEquals("CATALOG_UNSUPPORTED_SCHEMA",
                parse(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2")).errorCode());
    }

    /** §13.2: повторный ID из разных источников — явный конфликт, не молчаливая замена. */
    @Test
    public void rejectsDuplicateIds() {
        String crafted = "{\"schemaVersion\":1,\"name\":\"c\",\"modules\":["
                + "{\"id\":\"a.module\",\"name\":\"One\",\"description\":\"d\","
                + "\"manifestUrl\":\"https://one.example/m.json\"},"
                + "{\"id\":\"a.module\",\"name\":\"Two\",\"description\":\"d\","
                + "\"manifestUrl\":\"https://two.example/m.json\"}]}";
        Result<CatalogIndex> result = parse(crafted);
        assertEquals("CATALOG_DUPLICATE_ID", result.errorCode());
        assertTrue(result.errorMessage().contains("выбор источника"));
    }

    @Test
    public void rejectsNonHttpsManifestUrls() {
        assertEquals("CATALOG_BAD_URL",
                parse(VALID.replace("https://author.example/module.json",
                        "http://author.example/module.json")).errorCode());
    }

    @Test
    public void rejectsUnknownFieldsAndBadIds() {
        assertEquals("CATALOG_UNKNOWN_FIELD",
                parse(VALID.replace("\"name\":\"TikTok Moduled Modules\"",
                        "\"name\":\"TikTok Moduled Modules\",\"future\":1")).errorCode());
        assertEquals("CATALOG_BAD_ID",
                parse(VALID.replace("org.author.example", "../evil")).errorCode());
        assertEquals("CATALOG_BAD_JSON", parse("{oops").errorCode());
        assertEquals("CATALOG_EMPTY", CatalogIndex.validate(new byte[0]).errorCode());
    }

    @Test
    public void rejectsOversizedCatalog() {
        StringBuilder sb = new StringBuilder("{\"schemaVersion\":1,\"name\":\"n\",\"modules\":[");
        for (int i = 0; i < 40000; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"m").append(i)
                    .append("\",\"name\":\"n\",\"description\":\"d\",\"manifestUrl\":\"https://x.example/m.json\"}");
        }
        sb.append("]}");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length > Limits.MAX_CATALOG_BYTES);
        assertEquals("CATALOG_TOO_LARGE", CatalogIndex.validate(bytes).errorCode());
    }

    @Test
    public void acceptsEmptyModuleList() {
        Result<CatalogIndex> result = parse("{\"schemaVersion\":1,\"name\":\"empty\",\"modules\":[]}");
        assertTrue(result.isOk());
        assertEquals(List.of(), result.value().modules);
        assertTrue(result.value().search("что угодно").isEmpty());
    }
}
