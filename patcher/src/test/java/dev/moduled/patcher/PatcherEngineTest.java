package dev.moduled.patcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.moduled.core.Result;
import dev.moduled.patcher.xml.BinaryXmlReader;
import dev.moduled.patcher.xml.XmlElement;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Сквозной тест патчера (ТЗ §7 PAT-01…PAT-11):
 * анализ → план → пересборка → подпись → проверка → публикация.
 * Исходный APK не изменяется; лишние ABI исключаются; launcher, authorities и
 * package ID сохраняются; повторный патч распознаётся и идемпотентен.
 */
public class PatcherEngineTest {

    private static final String PACKAGE = "com.example.host";
    private static final long VERSION_CODE = 30430000L;
    private static final String VERSION_NAME = "30.4.3";
    private static final String APP_CLASS = "com.example.host.HostApplication";
    private static final String AUTHORITIES = "com.example.host.fileprovider";
    private static final String LAUNCHER = "com.example.host.LauncherActivity";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File inputApk;
    private RuntimePayload payload;
    private PatcherEngine engine;
    private File keyDir;
    private File outDir;

    @Before
    public void setUp() throws Exception {
        inputApk = buildFakeApk();
        File work = folder.newFolder("work");
        File payloadDir = folder.newFolder("payload");
        File dex = new File(payloadDir, "runtime-classes.dex");
        Files.write(dex.toPath(), fakeDex());
        File arm64 = new File(payloadDir, "libtmoduled-hook-arm64.so");
        Files.write(arm64.toPath(), "fake-native-arm64".getBytes(StandardCharsets.UTF_8));
        File armv7 = new File(payloadDir, "libtmoduled-hook-armv7.so");
        Files.write(armv7.toPath(), "fake-native-armv7".getBytes(StandardCharsets.UTF_8));
        payload = RuntimePayload.builder()
                .dex(dex)
                .nativeLibrary("arm64-v8a", arm64)
                .nativeLibrary("armeabi-v7a", armv7)
                .runtimeVersion("0.1.0")
                .adapterName("tiktok-generic")
                .build();
        engine = new PatcherEngine(payload, work);
        keyDir = folder.newFolder("keys");
        outDir = folder.newFolder("out");
    }

    private byte[] fakeDex() {
        // Заголовок DEX: magic + минимально правдоподобные поля (ТЗ §10.4).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = new byte[0x70];
        System.arraycopy(new byte[] {'d', 'e', 'x', '\n', '0', '3', '5', '\0'}, 0, header, 0, 8);
        header[8] = 0; header[9] = 0; header[10] = 0; header[11] = 0; // signature placeholder
        System.arraycopy(new byte[] {0x70, 0, 0, 0}, 0, header, 36, 4); // header_size
        System.arraycopy(new byte[] {0x78, 0x56, 0x34, 0x12}, 0, header, 40, 4); // endian tag
        out.write(header, 0, header.length);
        return out.toByteArray();
    }

    private File buildFakeApk() throws Exception {
        File apk = folder.newFile("host.apk");
        byte[] manifest = TestAxml.manifest(PACKAGE, VERSION_CODE, VERSION_NAME,
                APP_CLASS, AUTHORITIES, LAUNCHER, 28, 35);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(apk))) {
            addDeflated(out, "AndroidManifest.xml", manifest);
            addDeflated(out, "classes.dex", fakeDex());
            addDeflated(out, "classes2.dex", fakeDex());
            addDeflated(out, "resources.arsc", new byte[] {1, 2, 3, 4});
            addDeflated(out, "lib/arm64-v8a/libhost.so", "host-arm64".getBytes(StandardCharsets.UTF_8));
            addDeflated(out, "lib/armeabi-v7a/libhost.so", "host-armv7".getBytes(StandardCharsets.UTF_8));
            addDeflated(out, "lib/x86_64/libhost.so", "host-x86_64".getBytes(StandardCharsets.UTF_8));
            addDeflated(out, "res/layout/main.xml", "<x/>".getBytes(StandardCharsets.UTF_8));
            addStored(out, "META-INF/CERT.RSA", "old-signature".getBytes(StandardCharsets.UTF_8));
            addStored(out, "META-INF/CERT.SF", "old-signature-sf".getBytes(StandardCharsets.UTF_8));
        }
        return apk;
    }

    private void addDeflated(ZipOutputStream out, String name, byte[] content) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        out.putNextEntry(entry);
        out.write(content);
        out.closeEntry();
    }

    private void addStored(ZipOutputStream out, String name, byte[] content) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        CRC32 crc = new CRC32();
        crc.update(content);
        entry.setCrc(crc.getValue());
        out.putNextEntry(entry);
        out.write(content);
        out.closeEntry();
    }

    private CompatibilityProfile matchingProfile() {
        return CompatibilityProfile.builder("tiktok-arm64")
                .packageId(PACKAGE)
                .versionName(VERSION_NAME)
                .versionCodes(VERSION_CODE)
                .supportedAbis(AbiSupport.ARM64_V8A, AbiSupport.ARMEABI_V7A)
                .abiName("arm64-v8a")
                .baseApkOnly(true)
                .note("Тестовый профиль")
                .build();
    }

    private Result<PatchPlan> planDefault() {
        return engine.plan(inputApk, matchingProfile(),
                Arrays.asList(AbiSupport.ARM64_V8A),
                null, "AA:BB:CC:DD", null);
    }

    @Test
    public void analysisReadsHostApk() {
        Result<ApkAnalysis> analysis = ApkAnalyzer.analyze(inputApk);
        assertTrue(analysis.errorMessage(), analysis.isOk());
        ApkAnalysis value = analysis.value();
        assertEquals(PACKAGE, value.packageId);
        assertEquals(VERSION_NAME, value.versionName);
        assertEquals(VERSION_CODE, value.versionCode);
        assertEquals(Integer.valueOf(28), value.minSdkVersion);
        assertEquals(Integer.valueOf(35), value.targetSdkVersion);
        assertTrue(value.abis.contains("arm64-v8a"));
        assertEquals(Arrays.asList("classes.dex", "classes2.dex"), value.dexEntries);
        assertEquals(Arrays.asList(AUTHORITIES), value.providerAuthorities);
        assertEquals(Arrays.asList(LAUNCHER), value.launcherActivities);
        assertEquals(APP_CLASS, value.applicationName);
        assertTrue(value.hasResourcesArsc);
        assertFalse(value.alreadyPatched());
        assertFalse(value.looksLikeSplit);
        assertEquals(64, value.sha256.length());
    }

    @Test
    public void planIncludesAbiExclusionAndEntryPoint() {
        Result<PatchPlan> planned = planDefault();
        assertTrue(planned.errorMessage(), planned.isOk());
        PatchPlan plan = planned.value();
        assertTrue(plan.toText(), plan.canProceed());
        assertEquals(PACKAGE, plan.targetPackageId);
        assertFalse(plan.repatch);
        assertEquals(Arrays.asList("arm64-v8a"), plan.keptAbis);
        assertTrue(plan.excludedEntries.contains("lib/armeabi-v7a/libhost.so"));
        assertTrue(plan.excludedEntries.contains("lib/x86_64/libhost.so"));
        assertFalse(plan.excludedEntries.contains("lib/arm64-v8a/libhost.so"));
        assertTrue(plan.addedEntries.contains(RuntimePayload.ENTRY_DEX));
        assertTrue(plan.addedEntries.contains(RuntimePayload.ENTRY_ADAPTER));
        assertTrue(plan.addedEntries.contains("lib/arm64-v8a/libtmoduled-hook.so"));
        assertFalse(plan.addedEntries.contains("lib/armeabi-v7a/libtmoduled-hook.so"));
        assertNotNull(plan.entryPoint);
        assertEquals("dev.moduled.runtime.RuntimeApplication",
                plan.entryPoint.runtimeClassName);
        assertEquals(APP_CLASS, plan.entryPoint.originalApplicationName);
        assertEquals(EntryPointAnalysis.Strategy.WRAP_EXISTING_APPLICATION,
                plan.entryPoint.strategy);
        assertTrue(plan.signatureFingerprint.contains("AA:BB"));
    }

    @Test
    public void planBlocksOnProfileMismatch() {
        CompatibilityProfile wrong = CompatibilityProfile.builder("other")
                .packageId("com.other.app")
                .versionCodes(1L)
                .abiName("arm64-v8a")
                .build();
        Result<PatchPlan> planned = engine.plan(inputApk, wrong,
                Arrays.asList(AbiSupport.ARM64_V8A), null, "AA", null);
        assertTrue(planned.isOk());
        assertFalse(planned.value().canProceed());
        String text = planned.value().toText();
        assertTrue(text.contains("package ID"));
        assertTrue(text.contains("БЛОКЕР"));
    }

    @Test
    public void planBlocksOnPackageIdChange() {
        Result<PatchPlan> planned = engine.plan(inputApk, matchingProfile(),
                Arrays.asList(AbiSupport.ARM64_V8A), null, "AA", "com.example.host.clone");
        assertTrue(planned.isOk());
        assertFalse("смена package ID — отдельный профиль (§7.7)",
                planned.value().canProceed());
        assertTrue(planned.value().toText().contains("отдельного профиля"));
    }

    @Test
    public void planBlocksOnSignatureConflictBeforeInstall() {
        Result<PatchPlan> planned = engine.plan(inputApk, matchingProfile(),
                Arrays.asList(AbiSupport.ARM64_V8A),
                "11:22:33:44", "AA:BB:CC:DD", null);
        assertTrue(planned.isOk());
        assertFalse("конфликт подписи обязан блокировать патч до установки (§7.8)",
                planned.value().canProceed());
        assertTrue(planned.value().toText().contains("конфликт подписи"));
    }

    @Test
    public void planAllowsUpdateWithSameSignature() {
        Result<PatchPlan> planned = engine.plan(inputApk, matchingProfile(),
                Arrays.asList(AbiSupport.ARM64_V8A),
                "AA:BB:CC:DD", "aa:bb:cc:dd", null);
        assertTrue(planned.isOk());
        assertTrue(planned.value().canProceed());
        assertTrue(planned.value().signatureKeyReused);
    }

    @Test
    public void patchProducesSignedVerifiedApk() throws Exception {
        Result<PatchPlan> planned = planDefault();
        assertTrue(planned.isOk());
        String inputHashBefore = planned.value().analysis.sha256;

        Result<PatchOutcome> patched = engine.patch(planned.value(), inputApk, keyDir,
                SigningKeys.DEFAULT_ALIAS, "host-patched.apk", outDir);
        assertTrue(patched.errorMessage(), patched.isOk());
        PatchOutcome outcome = patched.value();
        assertTrue(outcome.toReportText(), outcome.succeeded());
        File output = outcome.output;
        assertNotNull(output);
        assertTrue(output.isFile());
        assertEquals(64, outcome.sha256.length());
        assertEquals(PACKAGE, outcome.packageId);
        assertTrue(outcome.signatureFingerprint.contains(":"));

        // §7.1: исходный APK не изменён.
        Result<ApkAnalysis> afterAnalysis = ApkAnalyzer.analyze(inputApk);
        assertTrue(afterAnalysis.isOk());
        assertEquals(inputHashBefore, afterAnalysis.value().sha256);

        List<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(output)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                entries.add(enumeration.nextElement().getName());
            }
        }
        // Записи платформы добавлены, лишние ABI и старая подпись исключены.
        assertTrue(entries.contains(RuntimePayload.ENTRY_DEX));
        assertTrue(entries.contains(RuntimePayload.ENTRY_ADAPTER));
        assertTrue(entries.contains(RuntimePayload.ENTRY_MARKER));
        assertTrue(entries.contains("lib/arm64-v8a/libtmoduled-hook.so"));
        assertFalse(entries.contains("lib/armeabi-v7a/libhost.so"));
        assertFalse(entries.contains("lib/x86_64/libhost.so"));
        assertFalse(entries.contains("META-INF/CERT.RSA"));
        // Собственные записи хоста сохранены.
        assertTrue(entries.contains("classes.dex"));
        assertTrue(entries.contains("classes2.dex"));
        assertTrue(entries.contains("resources.arsc"));
        assertTrue(entries.contains("res/layout/main.xml"));
        assertTrue(entries.contains("lib/arm64-v8a/libhost.so"));

        // Manifest: точка запуска заменена, launcher/authorities/package сохранены.
        try (ZipFile zip = new ZipFile(output)) {
            ZipEntry manifestEntry = zip.getEntry("AndroidManifest.xml");
            assertNotNull(manifestEntry);
            ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
            try (java.io.InputStream in = zip.getInputStream(manifestEntry)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    manifestBytes.write(buffer, 0, read);
                }
            }
            Result<dev.moduled.patcher.xml.XmlDocument> parsed =
                    BinaryXmlReader.parse(manifestBytes.toByteArray());
            assertTrue(parsed.errorMessage(), parsed.isOk());
            XmlElement root = parsed.value().root;
            assertEquals(PACKAGE, root.stringAttribute("package"));
            assertEquals(VERSION_NAME, root.stringAttribute("versionName"));
            XmlElement application = root.firstChild("application");
            assertEquals(ManifestEditor.RUNTIME_APPLICATION, application.stringAttribute("name"));
            boolean launcherFound = false;
            boolean recoveryFound = false;
            for (XmlElement activity : application.descendantsNamed("activity")) {
                String name = activity.stringAttribute("name");
                if (LAUNCHER.equals(name)) {
                    launcherFound = true;
                    assertEquals("true", activity.stringAttribute("exported"));
                }
                if (ManifestEditor.SAFE_MODE_ACTIVITY.equals(name)) {
                    recoveryFound = true;
                    assertEquals("false", activity.stringAttribute("exported"));
                }
            }
            assertTrue("launcher обязан сохраниться (PAT-10)", launcherFound);
            assertTrue("экран восстановления обязан быть объявлен", recoveryFound);
            assertEquals(AUTHORITIES, application.descendantsNamed("provider")
                    .get(0).stringAttribute("authorities"));
        }

        // Подпись валидна независимым верификатором apksig.
        com.android.apksig.ApkVerifier.Result verification =
                new com.android.apksig.ApkVerifier.Builder(output).build().verify();
        assertTrue("подпись патченного APK обязана проходить проверку: "
                + verification.getErrors(), verification.isVerified());

        // adapter.json содержит сведения для runtime и оригинальный Application.
        try (ZipFile zip = new ZipFile(output)) {
            ZipEntry adapterEntry = zip.getEntry(RuntimePayload.ENTRY_ADAPTER);
            assertNotNull(adapterEntry);
            ByteArrayOutputStream adapterBytes = new ByteArrayOutputStream();
            try (java.io.InputStream in = zip.getInputStream(adapterEntry)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    adapterBytes.write(buffer, 0, read);
                }
            }
            String json = new String(adapterBytes.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"targetPackage\":\"" + PACKAGE + '"'));
            assertTrue(json.contains("\"originalApplication\":\"" + APP_CLASS + '"'));
            assertTrue(json.contains("\"adapterName\":\"tiktok-generic\""));
            assertTrue(json.contains("\"supported\":true"));
        }
        // Отчёт не содержит секретов: ни пароля, ни приватного ключа.
        String report = outcome.toReportText();
        assertFalse(report.toLowerCase(java.util.Locale.ROOT).contains("private"));
        assertFalse(report.contains("BEGIN"));
    }

    @Test
    public void repatchIsDetectedAndIdempotent() throws Exception {
        Result<PatchPlan> first = planDefault();
        assertTrue(first.isOk());
        Result<PatchOutcome> firstPatch = engine.patch(first.value(), inputApk, keyDir,
                SigningKeys.DEFAULT_ALIAS, "host-patched.apk", outDir);
        assertTrue(firstPatch.errorMessage(), firstPatch.isOk());
        File patched = firstPatch.value().output;

        Result<PatchPlan> second = engine.plan(patched, matchingProfile(),
                Arrays.asList(AbiSupport.ARM64_V8A), null, "AA:BB:CC:DD", null);
        assertTrue(second.errorMessage(), second.isOk());
        assertTrue("повторный патч обязан распознаваться (PAT-11)", second.value().repatch);
        assertTrue(second.value().canProceed());
        boolean hasReplace = false;
        for (ConflictResolver.Conflict conflict : second.value().conflicts) {
            if (conflict.decision == ConflictResolver.Decision.REPLACE) {
                hasReplace = true;
            }
            assertFalse("при повторном патче не должно быть неразрешимых конфликтов",
                    conflict.decision == ConflictResolver.Decision.REJECT);
        }
        assertTrue("записи платформы заменяются, а не дублируются", hasReplace);

        Result<PatchOutcome> secondPatch = engine.patch(second.value(), patched, keyDir,
                SigningKeys.DEFAULT_ALIAS, "host-repatched.apk", outDir);
        assertTrue(secondPatch.errorMessage(), secondPatch.isOk());
        assertTrue(secondPatch.value().succeeded());
        assertTrue(secondPatch.value().repatch);
        // Ключ тот же: обновления ставятся без удаления (§7.8).
        assertEquals(firstPatch.value().signatureFingerprint,
                secondPatch.value().signatureFingerprint);

        // В итоговом APK нет дублей записей платформы и ровно один recovery-экран.
        try (ZipFile zip = new ZipFile(secondPatch.value().output)) {
            int dexCount = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (RuntimePayload.ENTRY_DEX.equals(entries.nextElement().getName())) {
                    dexCount++;
                }
            }
            assertEquals(1, dexCount);
        }
        com.android.apksig.ApkVerifier.Result verification =
                new com.android.apksig.ApkVerifier.Builder(secondPatch.value().output)
                        .build().verify();
        assertTrue(verification.isVerified());
    }

    @Test
    public void patchWithoutPlanBlockersOnlyIsRefused() {
        Result<PatchPlan> planned = engine.plan(inputApk, matchingProfile(),
                Arrays.asList(AbiSupport.ARM64_V8A), "11:22", "AA:BB", null);
        assertTrue(planned.isOk());
        assertFalse(planned.value().canProceed());
        Result<PatchOutcome> patched = engine.patch(planned.value(), inputApk, keyDir,
                SigningKeys.DEFAULT_ALIAS, "host-patched.apk", outDir);
        assertTrue(patched.isOk());
        assertFalse("патч с блокерами не должен публиковать файл",
                patched.value().succeeded());
        assertEquals(null, patched.value().output);
        assertTrue(patched.value().toReportText().contains("БЛОКЕР"));
    }
}
