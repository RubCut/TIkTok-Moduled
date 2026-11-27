package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArchiveValidatorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String VALID_MANIFEST = "{"
            + "\"id\":\"sample.module\","
            + "\"name\":\"Sample\","
            + "\"description\":\"demo\","
            + "\"version\":\"1.0.0\","
            + "\"versionCode\":1,"
            + "\"apiVersion\":1,"
            + "\"entrypoint\":\"dev.moduled.sample.SampleModule\","
            + "\"targetPackages\":[\"com.zhiliaoapp.musically\"]}";

    private Path validPackage() throws IOException {
        Path dir = tmp.newFolder("pkg").toPath();
        return TestZip.write(dir, "module.zip", List.of(
                TestZip.entry("module.json", VALID_MANIFEST),
                TestZip.entry("classes.dex", DexValidatorTest.validDex())));
    }

    /** PKG-01/PKG-04: корректный пакет v1 принимается, содержимое читается байт в байт. */
    @Test
    public void acceptsValidV1Package() throws IOException {
        Path zip = validPackage();
        Result<ModuleManifest> result = ArchiveValidator.validateModulePackage(zip);
        assertTrue(result.errorMessage(), result.isOk());
        assertEquals("sample.module", result.value().id);

        ArchiveValidator.ValidatedArchive archive = ArchiveValidator.validate(zip).value();
        assertEquals(2, archive.entries.size());
        assertArrayEquals(VALID_MANIFEST.getBytes(StandardCharsets.UTF_8), archive.get("module.json"));
    }

    /** PKG-01: ровно два файла в корне — всё остальное требует v2. */
    @Test
    public void rejectsLayoutViolations() throws IOException {
        Path dir = tmp.newFolder("layout").toPath();

        Path extraFile = TestZip.write(dir, "extra.zip", List.of(
                TestZip.entry("module.json", VALID_MANIFEST),
                TestZip.entry("classes.dex", DexValidatorTest.validDex()),
                TestZip.entry("extra.txt", "hello")));
        assertEquals("PACKAGE_LAYOUT", ArchiveValidator.validateModulePackage(extraFile).errorCode());

        Path secondDex = TestZip.write(dir, "multidex.zip", List.of(
                TestZip.entry("module.json", VALID_MANIFEST),
                TestZip.entry("classes.dex", DexValidatorTest.validDex()),
                TestZip.entry("classes2.dex", DexValidatorTest.validDex())));
        assertEquals("PACKAGE_LAYOUT", ArchiveValidator.validateModulePackage(secondDex).errorCode());

        Path nested = TestZip.write(dir, "nested.zip", List.of(
                TestZip.entry("sub/module.json", VALID_MANIFEST),
                TestZip.entry("classes.dex", DexValidatorTest.validDex())));
        assertEquals("PACKAGE_LAYOUT", ArchiveValidator.validateModulePackage(nested).errorCode());

        Path nativeLib = TestZip.write(dir, "native.zip", List.of(
                TestZip.entry("module.json", VALID_MANIFEST),
                TestZip.entry("classes.dex", DexValidatorTest.validDex()),
                TestZip.entry("lib/arm64-v8a/libx.so", new byte[] {1, 2, 3})));
        assertEquals("PACKAGE_LAYOUT", ArchiveValidator.validateModulePackage(nativeLib).errorCode());
    }

    /** PKG-01: небезопасные имена записей отклоняются до распаковки. */
    @Test
    public void rejectsUnsafeEntryNames() throws IOException {
        Path dir = tmp.newFolder("names").toPath();
        String[][] cases = {
                {"../evil.json", "ARCHIVE_PATH_TRAVERSAL"},
                {"sub/../../evil.json", "ARCHIVE_PATH_TRAVERSAL"},
                {"./evil.json", "ARCHIVE_PATH_TRAVERSAL"},
                {"/etc/passwd", "ARCHIVE_BAD_NAME"},
                {"\\windows\\system32", "ARCHIVE_BAD_NAME"},
                {"a\\b.json", "ARCHIVE_BAD_NAME"},
                {"C:drive.json", "ARCHIVE_BAD_NAME"},
                {"a//b.json", "ARCHIVE_BAD_NAME"},
        };
        for (String[] testCase : cases) {
            Path zip = TestZip.write(dir, "case" + testCase[0].hashCode() + ".zip", List.of(
                    TestZip.entry(testCase[0], VALID_MANIFEST),
                    TestZip.entry("classes.dex", DexValidatorTest.validDex())));
            Result<ArchiveValidator.ValidatedArchive> result = ArchiveValidator.validate(zip);
            assertTrue("Entry '" + testCase[0] + "' must be rejected", !result.isOk());
            assertEquals("Entry '" + testCase[0] + "' -> " + result.errorMessage(),
                    testCase[1], result.errorCode());
        }
    }

    @Test
    public void rejectsDuplicateEntries() throws IOException {
        Path dir = tmp.newFolder("dup").toPath();
        Path zip = TestZip.write(dir, "dup.zip", List.of(
                TestZip.entry("module.json", VALID_MANIFEST),
                TestZip.entry("module.json", VALID_MANIFEST)));
        assertEquals("ARCHIVE_DUPLICATE_ENTRY", ArchiveValidator.validate(zip).errorCode());
    }

    /** PKG-02: шифрованные записи запрещены. */
    @Test
    public void rejectsEncryptedEntry() throws IOException {
        Path dir = tmp.newFolder("enc").toPath();
        Path zip = TestZip.write(dir, "enc.zip", List.of(
                TestZip.entry("module.json", VALID_MANIFEST),
                TestZip.entry("classes.dex", DexValidatorTest.validDex()).encrypted()));
        assertEquals("ARCHIVE_ENCRYPTED", ArchiveValidator.validate(zip).errorCode());
    }

    /** PKG-02: повреждённые данные / несовпадение CRC — отказ, а не частичная распаковка. */
    @Test
    public void rejectsCorruptCrc() throws IOException {
        Path dir = tmp.newFolder("crc").toPath();
        Path zip = TestZip.write(dir, "crc.zip", List.of(
                TestZip.entry("module.json", VALID_MANIFEST),
                TestZip.entry("classes.dex", DexValidatorTest.validDex()).corruptCrc()));
        Result<ArchiveValidator.ValidatedArchive> result = ArchiveValidator.validate(zip);
        assertTrue(!result.isOk());
        assertEquals("ARCHIVE_CORRUPT", result.errorCode());
    }

    @Test
    public void rejectsNonZipAndMissingFiles() throws IOException {
        Path dir = tmp.newFolder("notzip").toPath();
        Path garbage = dir.resolve("garbage.zip");
        Files.write(garbage, "this is not a zip".getBytes(StandardCharsets.UTF_8));
        assertEquals("ARCHIVE_CORRUPT", ArchiveValidator.validate(garbage).errorCode());

        Path truncated = dir.resolve("truncated.zip");
        byte[] full = TestZip.build(List.of(TestZip.entry("module.json", VALID_MANIFEST)));
        Files.write(truncated, Arrays.copyOf(full, full.length / 2));
        Result<ArchiveValidator.ValidatedArchive> result = ArchiveValidator.validate(truncated);
        assertTrue(!result.isOk());
        assertTrue(result.errorCode().startsWith("ARCHIVE_"));

        assertEquals("ARCHIVE_MISSING",
                ArchiveValidator.validate(dir.resolve("absent.zip")).errorCode());
        assertEquals("ARCHIVE_MISSING", ArchiveValidator.validate(null).errorCode());
    }

    /** PKG-02: поддерживаются STORED и DEFLATE; прочие методы отклоняются. */
    @Test
    public void acceptsDeflatedEntries() throws IOException {
        Path dir = tmp.newFolder("deflate").toPath();
        Path zip = dir.resolve("deflated.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("module.json"));
            out.write(VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("classes.dex"));
            out.write(DexValidatorTest.validDex());
            out.closeEntry();
        }
        Result<ModuleManifest> result = ArchiveValidator.validateModulePackage(zip);
        assertTrue(result.errorMessage(), result.isOk());
        assertEquals("sample.module", result.value().id);
    }

    /** PKG-03: manifest больше 256 KiB отклоняется по лимиту записи. */
    @Test
    public void rejectsOversizedManifestEntry() throws IOException {
        Path dir = tmp.newFolder("bigmanifest").toPath();
        StringBuilder sb = new StringBuilder(VALID_MANIFEST.substring(0, VALID_MANIFEST.length() - 1));
        sb.append(",\"pad\":\"");
        for (int i = 0; i < Limits.MAX_DESCRIPTOR_BYTES; i++) {
            sb.append('x');
        }
        sb.append("\"}");
        Path zip = TestZip.write(dir, "big.zip", List.of(
                TestZip.entry("module.json", sb.toString()),
                TestZip.entry("classes.dex", DexValidatorTest.validDex())));
        Result<ArchiveValidator.ValidatedArchive> result = ArchiveValidator.validate(zip);
        assertTrue(!result.isOk());
        assertEquals("ARCHIVE_ENTRY_TOO_LARGE", result.errorCode());
        assertTrue(result.errorMessage().contains("exceeds limit"));
    }

    /** PKG-04: содержимое module.json внутри архива тоже валидируется. */
    @Test
    public void rejectsBrokenManifestInsideArchive() throws IOException {
        Path dir = tmp.newFolder("badmanifest").toPath();
        Path zip = TestZip.write(dir, "bad.zip", List.of(
                TestZip.entry("module.json", "{ not json"),
                TestZip.entry("classes.dex", DexValidatorTest.validDex())));
        assertEquals("MANIFEST_BAD_JSON", ArchiveValidator.validateModulePackage(zip).errorCode());
    }

    @Test
    public void rejectsBrokenDexInsideArchive() throws IOException {
        Path dir = tmp.newFolder("baddex").toPath();
        Path zip = TestZip.write(dir, "baddex.zip", List.of(
                TestZip.entry("module.json", VALID_MANIFEST),
                TestZip.entry("classes.dex", "plain text, not dex".getBytes(StandardCharsets.UTF_8))));
        Result<ModuleManifest> result = ArchiveValidator.validateModulePackage(zip);
        assertTrue(!result.isOk());
        assertTrue(result.errorCode().startsWith("DEX_"));
    }
}
