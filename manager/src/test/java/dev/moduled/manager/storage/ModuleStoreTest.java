package dev.moduled.manager.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.moduled.core.Limits;
import dev.moduled.core.Result;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Хранилище модулей (ТЗ §11, §13.3, PKG-05/PKG-06, CFG-03..CFG-06, CFG-11).
 */
public class ModuleStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ModuleStore store;
    private File packages;

    @Before
    public void setUp() throws IOException {
        store = new ModuleStore(tmp.newFolder("modules"));
        packages = tmp.newFolder("packages");
    }

    private Result<InstallOutcome> install(File archive) {
        return store.install(InstallRequest.fromFile(archive).build());
    }

    @Test
    public void installsNewModuleDisabledByDefault() throws IOException {
        File archive = TestModulePackage.buildValid(packages, "sample.zip",
                "sample.module", "1.0.0", 1);
        Result<InstallOutcome> result = install(archive);
        assertTrue(result.errorMessage(), result.isOk());
        ModuleRecord record = result.value().record;
        assertEquals("sample.module", record.id());
        assertFalse("Новый сторонний модуль устанавливается выключенным (§11)",
                record.state.enabled);
        assertFalse(result.value().update);
        assertTrue(new File(record.directory, "classes.dex").isFile());
        assertTrue(new File(record.directory, "module.json").isFile());
        assertTrue(new File(record.directory, "config.txt").isFile());
        assertFalse("DEX hash must be computed", record.dexSha256.isEmpty());
    }

    @Test
    public void rejectsBrokenPackages() throws IOException {
        File badManifest = TestModulePackage.build(packages, "bad-manifest.zip",
                "{ not json", TestModulePackage.validDex());
        assertEquals("MANIFEST_BAD_JSON", install(badManifest).errorCode());

        File badDex = TestModulePackage.build(packages, "bad-dex.zip",
                TestModulePackage.manifest("a.module", "1.0", 1, 1, "A", "dev.moduled.demo"),
                "not a dex".getBytes(StandardCharsets.UTF_8));
        assertTrue(install(badDex).errorCode().startsWith("DEX_"));

        File extraFile = new File(packages, "extra.zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(extraFile.toPath()))) {
            out.putNextEntry(new java.util.zip.ZipEntry("module.json"));
            out.write(TestModulePackage.manifest("a.module", "1.0", 1, 1, "A", "dev.moduled.demo")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("classes.dex"));
            out.write(TestModulePackage.validDex());
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("lib/arm64-v8a/libx.so"));
            out.write(new byte[] {1, 2, 3});
            out.closeEntry();
        }
        assertEquals("PACKAGE_LAYOUT", install(extraFile).errorCode());

        File zipSlip = new File(packages, "slip.zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(zipSlip.toPath()))) {
            out.putNextEntry(new java.util.zip.ZipEntry("../../evil.sh"));
            out.write("rm -rf".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertEquals("ARCHIVE_PATH_TRAVERSAL", install(zipSlip).errorCode());

        assertEquals("INSTALL_NO_INPUT", store.install(
                InstallRequest.fromFile(new File(packages, "absent.zip")).build()).errorCode());
    }

    /** PKG-06: установка поверх существующего ID требует подтверждения. */
    @Test
    public void replacingExistingIdRequiresConfirmation() throws IOException {
        File first = TestModulePackage.buildValid(packages, "v1.zip", "sample.module", "1.0.0", 1);
        assertTrue(install(first).isOk());
        File second = TestModulePackage.buildValid(packages, "v2.zip", "sample.module", "1.1.0", 2);

        Result<InstallOutcome> blocked = install(second);
        assertFalse(blocked.isOk());
        assertEquals("INSTALL_REQUIRES_CONFIRMATION", blocked.errorCode());
        assertTrue(blocked.errorMessage().contains("не доказывает"));

        Result<InstallOutcome> confirmed = store.install(
                InstallRequest.fromFile(second).confirmReplace(true).build());
        assertTrue(confirmed.errorMessage(), confirmed.isOk());
        assertTrue(confirmed.value().update);
        assertEquals(2L, confirmed.value().record.manifest.versionCode);
    }

    /** §14.2: смена авторского ключа требует нового согласия. */
    @Test
    public void keyChangeRequiresConsent() throws IOException {
        File first = TestModulePackage.buildValid(packages, "v1.zip", "sample.module", "1.0.0", 1);
        assertTrue(store.install(InstallRequest.fromFile(first)
                .authorKeyFingerprint("a".repeat(64))
                .trustConfirmed(true)
                .build()).isOk());

        File second = TestModulePackage.buildValid(packages, "v2.zip", "sample.module", "1.1.0", 2);
        Result<InstallOutcome> blocked = store.install(InstallRequest.fromFile(second)
                .confirmReplace(true)
                .authorKeyFingerprint("b".repeat(64))
                .build());
        assertEquals("INSTALL_KEY_CHANGED", blocked.errorCode());

        Result<InstallOutcome> confirmed = store.install(InstallRequest.fromFile(second)
                .confirmReplace(true)
                .confirmNewKey(true)
                .authorKeyFingerprint("b".repeat(64))
                .build());
        assertTrue(confirmed.isOk());
        assertEquals("b".repeat(64), confirmed.value().record.state.authorKeyFingerprint);
    }

    /** §13.3: обновление сохраняет конфиг и данные. */
    @Test
    public void updatePreservesConfig() throws IOException {
        File first = TestModulePackage.buildValid(packages, "v1.zip", "sample.module", "1.0.0", 1);
        assertTrue(install(first).isOk());
        byte[] config = "region=us\r\ncomment=сохранить".getBytes(StandardCharsets.UTF_8);
        assertTrue(store.writeConfig("sample.module", config).isOk());

        File second = TestModulePackage.buildValid(packages, "v2.zip", "sample.module", "1.1.0", 2);
        Result<InstallOutcome> updated = store.install(
                InstallRequest.fromFile(second).confirmReplace(true).build());
        assertTrue(updated.isOk());
        assertArrayEquals(config, store.readConfig("sample.module").value());
    }

    @Test
    public void enableDisableIsPersisted() throws IOException {
        File archive = TestModulePackage.buildValid(packages, "sample.zip",
                "sample.module", "1.0.0", 1);
        assertTrue(install(archive).isOk());
        assertTrue(store.setEnabled("sample.module", true).isOk());
        assertTrue(store.get("sample.module").value().state.enabled);
        assertTrue(store.setEnabled("sample.module", false).isOk());
        assertFalse(store.get("sample.module").value().state.enabled);
        assertEquals("MODULE_NOT_FOUND", store.setEnabled("absent.module", true).errorCode());
    }

    /** §11: безопасное значение по умолчанию — сохранить конфиг; CFG-11: восстановление. */
    @Test
    public void uninstallKeepsConfigAndReinstallRestoresIt() throws IOException {
        File archive = TestModulePackage.buildValid(packages, "sample.zip",
                "sample.module", "1.0.0", 1);
        assertTrue(install(archive).isOk());
        byte[] config = "ключ=значение".getBytes(StandardCharsets.UTF_8);
        assertTrue(store.writeConfig("sample.module", config).isOk());

        assertTrue(store.uninstall("sample.module", true).isOk());
        assertFalse(store.get("sample.module").isOk());
        assertTrue(store.hasKeptConfig("sample.module"));

        Result<InstallOutcome> reinstalled = install(archive);
        assertTrue(reinstalled.isOk());
        assertTrue("Пользователю показывается восстановленный конфиг",
                reinstalled.value().configRestored);
        assertArrayEquals(config, store.readConfig("sample.module").value());
    }

    @Test
    public void uninstallWithoutKeepingConfigDropsIt() throws IOException {
        File archive = TestModulePackage.buildValid(packages, "sample.zip",
                "sample.module", "1.0.0", 1);
        assertTrue(install(archive).isOk());
        assertTrue(store.writeConfig("sample.module", "secret".getBytes(StandardCharsets.UTF_8)).isOk());
        assertTrue(store.uninstall("sample.module", false).isOk());
        assertFalse(store.hasKeptConfig("sample.module"));
        Result<InstallOutcome> reinstalled = install(archive);
        assertTrue(reinstalled.isOk());
        assertFalse(reinstalled.value().configRestored);
        assertEquals(0, store.readConfig("sample.module").value().length);
    }

    /** CFG-03/CFG-04: точные байты без преобразований. */
    @Test
    public void configIsStoredByteForByte() throws IOException {
        File archive = TestModulePackage.buildValid(packages, "sample.zip",
                "sample.module", "1.0.0", 1);
        assertTrue(install(archive).isOk());
        byte[] exact = "\uFEFFa = 1\r\n\tb=2  \r\nбез финального перевода".getBytes(StandardCharsets.UTF_8);
        assertTrue(store.writeConfig("sample.module", exact).isOk());
        assertArrayEquals(exact, store.readConfig("sample.module").value());

        // Пустой конфиг допустим (CFG-02).
        assertTrue(store.writeConfig("sample.module", new byte[0]).isOk());
        assertEquals(0, store.readConfig("sample.module").value().length);
    }

    @Test
    public void configOverLimitIsRejectedAndOldKept() throws IOException {
        File archive = TestModulePackage.buildValid(packages, "sample.zip",
                "sample.module", "1.0.0", 1);
        assertTrue(install(archive).isOk());
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        assertTrue(store.writeConfig("sample.module", original).isOk());

        byte[] tooBig = new byte[Limits.MAX_CONFIG_BYTES + 1];
        java.util.Arrays.fill(tooBig, (byte) 'x');
        Result<Void> result = store.writeConfig("sample.module", tooBig);
        assertEquals("CONFIG_TOO_LARGE", result.errorCode());
        assertArrayEquals("Старый конфиг не теряется при ошибке (CFG-06)",
                original, store.readConfig("sample.module").value());
    }

    @Test
    public void invalidUtf8ConfigIsRejected() throws IOException {
        File archive = TestModulePackage.buildValid(packages, "sample.zip",
                "sample.module", "1.0.0", 1);
        assertTrue(install(archive).isOk());
        byte[] invalid = new byte[] {'a', (byte) 0xC3, (byte) 0x28};
        assertEquals("CONFIG_NOT_UTF8", store.writeConfig("sample.module", invalid).errorCode());
    }

    @Test
    public void listIgnoresServiceDirectories() throws IOException {
        File archive = TestModulePackage.buildValid(packages, "sample.zip",
                "sample.module", "1.0.0", 1);
        assertTrue(install(archive).isOk());
        Files.createDirectories(store.root().resolve(".staging-junk"));
        Files.createDirectories(store.root().resolve(".previous-sample.module-1"));
        List<ModuleRecord> records = store.list().value();
        assertEquals(1, records.size());
        assertEquals("sample.module", records.get(0).id());
    }

    /** PKG-05: после отказа staging не остаётся в хранилище. */
    @Test
    public void stagingIsCleanedAfterFailure() throws IOException {
        File badDex = TestModulePackage.build(packages, "bad.zip",
                TestModulePackage.manifest("a.module", "1.0", 1, 1, "A", "dev.moduled.demo"),
                "plain text".getBytes(StandardCharsets.UTF_8));
        assertFalse(install(badDex).isOk());
        assertEquals(0, store.cleanStaging());
        try (var stream = Files.list(store.root())) {
            stream.forEach(path -> assertFalse("staging left behind: " + path,
                    path.getFileName().toString().startsWith(".staging-")));
        }
    }

    /** Повреждённое состояние читается консервативно: модуль выключен. */
    @Test
    public void corruptedStateFailsSafe() throws IOException {
        File archive = TestModulePackage.buildValid(packages, "sample.zip",
                "sample.module", "1.0.0", 1);
        assertTrue(install(archive).isOk());
        assertTrue(store.setEnabled("sample.module", true).isOk());
        Files.write(new File(store.root().resolve("sample.module").toFile(), "state.json").toPath(),
                "мусор вместо json".getBytes(StandardCharsets.UTF_8));
        ModuleRecord record = store.get("sample.module").value();
        assertFalse("Повреждённое состояние не включает модуль", record.state.enabled);
    }

    @Test
    public void bundledModuleCanBeEnabledOnInstall() throws IOException {
        File archive = TestModulePackage.buildValid(packages, "region.zip",
                "dev.moduled.region", "1.0.0", 1);
        Result<InstallOutcome> result = store.install(InstallRequest.bundled(archive)
                .enableAfterInstall(true)
                .trustConfirmed(true)
                .build());
        assertTrue(result.errorMessage(), result.isOk());
        assertTrue(result.value().record.state.bundled);
        assertTrue(result.value().enabled);
        assertEquals(ModuleState.Origin.BUNDLED, result.value().record.state.origin);
    }
}
