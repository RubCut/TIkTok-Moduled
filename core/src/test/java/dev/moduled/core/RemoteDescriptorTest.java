package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/**
 * §10.2: descriptor расширяет манифест транспортными полями; archiveSha256 не
 * является самоссылкой и не хранится внутри того же ZIP.
 */
public class RemoteDescriptorTest {

    private static final String VALID = "{"
            + "\"id\":\"org.author.example\","
            + "\"name\":\"Пример модуля\","
            + "\"description\":\"Описание\","
            + "\"version\":\"1.2.0\","
            + "\"versionCode\":12,"
            + "\"apiVersion\":1,"
            + "\"entrypoint\":\"org.author.example.MainModule\","
            + "\"targetPackages\":[\"com.zhiliaoapp.musically\"],"
            + "\"downloadUrl\":\"https://author.example/files/module-1.2.0.zip\","
            + "\"archiveSha256\":\"" + "a".repeat(64) + "\","
            + "\"iconUrl\":\"https://author.example/icon.png\","
            + "\"screenshots\":[\"https://author.example/s1.png\"],"
            + "\"authorName\":\"Author\","
            + "\"authorUrl\":\"https://author.example\","
            + "\"sourceUrl\":\"https://github.com/author/module\","
            + "\"authorKeyFingerprint\":\"" + "b".repeat(64) + "\""
            + "}";

    private static Result<RemoteDescriptor> parse(String json) {
        return RemoteDescriptor.validate(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void acceptsFullDescriptor() {
        Result<RemoteDescriptor> result = parse(VALID);
        assertTrue(result.errorMessage(), result.isOk());
        RemoteDescriptor descriptor = result.value();
        assertEquals("org.author.example", descriptor.manifest.id);
        assertEquals(12L, descriptor.manifest.versionCode);
        assertEquals("https://author.example/files/module-1.2.0.zip", descriptor.downloadUrl);
        assertEquals("a".repeat(64), descriptor.archiveSha256);
        assertEquals("https://author.example/icon.png", descriptor.iconUrl);
        assertEquals(List.of("https://author.example/s1.png"), descriptor.screenshots);
        assertEquals("Author", descriptor.authorName);
        assertEquals("b".repeat(64), descriptor.authorKeyFingerprint);
        assertNull(descriptor.signature);
    }

    @Test
    public void iconAndScreenshotsAreOptional() {
        String minimal = VALID
                .replace(",\"iconUrl\":\"https://author.example/icon.png\"", "")
                .replace(",\"screenshots\":[\"https://author.example/s1.png\"]", "")
                .replace(",\"authorName\":\"Author\"", "")
                .replace(",\"authorUrl\":\"https://author.example\"", "")
                .replace(",\"sourceUrl\":\"https://github.com/author/module\"", "")
                .replace(",\"authorKeyFingerprint\":\"" + "b".repeat(64) + "\"", "");
        Result<RemoteDescriptor> result = parse(minimal);
        assertTrue(result.errorMessage(), result.isOk());
        assertNull(result.value().iconUrl);
        assertTrue(result.value().screenshots.isEmpty());
        assertNull(result.value().authorName);
    }

    @Test
    public void rejectsMissingTransportFields() {
        assertEquals("DESCRIPTOR_MISSING_FIELD",
                parse(VALID.replace("\"id\":\"org.author.example\",", "")).errorCode());
        assertEquals("DESCRIPTOR_BAD_DOWNLOAD_URL",
                parse(VALID.replace(",\"downloadUrl\":\"https://author.example/files/module-1.2.0.zip\"", ""))
                        .errorCode());
        assertEquals("DESCRIPTOR_BAD_HASH",
                parse(VALID.replace(",\"archiveSha256\":\"" + "a".repeat(64) + "\"", "")).errorCode());
    }

    /** §13.2/§10.2: HTTP и самоссылки запрещены. */
    @Test
    public void rejectsNonHttpsDownloadUrl() {
        Result<RemoteDescriptor> result = parse(VALID.replace(
                "https://author.example/files/module-1.2.0.zip",
                "http://author.example/files/module-1.2.0.zip"));
        assertEquals("DESCRIPTOR_BAD_DOWNLOAD_URL", result.errorCode());
    }

    @Test
    public void rejectsBadHashesAndUnknownFields() {
        assertEquals("DESCRIPTOR_BAD_HASH",
                parse(VALID.replace("a".repeat(64), "short")).errorCode());
        assertEquals("DESCRIPTOR_UNKNOWN_FIELD",
                parse(VALID.replace(",\"signature\"", ",\"signatureV99\" ,\"signature\""))
                        .errorCode());
        assertEquals("DESCRIPTOR_BAD_FINGERPRINT",
                parse(VALID.replace("b".repeat(64), "zzz")).errorCode());
        assertEquals("DESCRIPTOR_BAD_JSON", parse("{").errorCode());
        assertEquals("DESCRIPTOR_EMPTY", RemoteDescriptor.validate(new byte[0]).errorCode());
    }

    /** Внутренний манифест не должен содержать транспортные поля (самоссылка). */
    @Test
    public void internalManifestRejectsTransportFields() {
        assertEquals("MANIFEST_UNKNOWN_FIELD",
                ManifestValidator.validate(VALID.getBytes(StandardCharsets.UTF_8)).errorCode());
    }

    @Test
    public void comparesCommonFieldsNotRawJson() {
        RemoteDescriptor descriptor = parse(VALID).value();
        ModuleManifest same = new ModuleManifest("org.author.example", "Пример модуля", "Описание",
                "1.2.0", 12, 1, "org.author.example.MainModule",
                List.of("com.zhiliaoapp.musically"));
        assertTrue(RemoteDescriptor.matchesArchiveManifest(descriptor, same).isOk());

        ModuleManifest otherVersion = new ModuleManifest("org.author.example", "Пример модуля",
                "Описание", "1.3.0", 13, 1, "org.author.example.MainModule",
                List.of("com.zhiliaoapp.musically"));
        Result<Void> mismatch = RemoteDescriptor.matchesArchiveManifest(descriptor, otherVersion);
        assertEquals("DESCRIPTOR_MISMATCH", mismatch.errorCode());
        assertTrue(mismatch.errorMessage().contains("version"));
        assertTrue(mismatch.errorMessage().contains("versionCode"));
    }

    @Test
    public void rejectsBadManifestFieldsInsideDescriptor() {
        assertEquals("DESCRIPTOR_UNSUPPORTED_API",
                parse(VALID.replace("\"apiVersion\":1", "\"apiVersion\":3")).errorCode());
        assertEquals("DESCRIPTOR_BAD_ID",
                parse(VALID.replace("\"id\":\"org.author.example\"", "\"id\":\"../evil\""))
                        .errorCode());
    }
}
