package dev.moduled.patcher;

import dev.moduled.core.Hashing;
import dev.moduled.core.Limits;
import dev.moduled.core.Result;
import dev.moduled.patcher.xml.BinaryXmlReader;
import dev.moduled.patcher.xml.XmlDocument;
import dev.moduled.patcher.xml.XmlElement;
import dev.moduled.patcher.xml.XmlValue;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Предварительный анализ входного APK (ТЗ §7.2 PAT-01, PAT-11).
 *
 * <p>Анализ читает только нужные записи: manifest целиком, остальные — по именам
 * и размерам. Исходный файл не изменяется (§7.1).</p>
 */
public final class ApkAnalyzer {

    /** Маркеры того, что вход уже изменён платформой (PAT-11). */
    public static final String MARKER_ADAPTER_ASSET = "assets/tmoduled/adapter.json";
    public static final String MARKER_RUNTIME_CLASS = "Ldev/moduled/runtime/RuntimeBootstrap;";
    public static final String MARKER_RECOVERY_ACTIVITY =
            "dev.moduled.runtime.SafeModeRecoveryActivity";

    /** Ограничение на размер читаемого manifest: 4 MiB достаточно для любого APK. */
    private static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;

    private ApkAnalyzer() {
    }

    public static Result<ApkAnalysis> analyze(File apk) {
        if (apk == null || !apk.isFile()) {
            return Result.fail("APK_MISSING", "Файл APK недоступен: " + apk);
        }
        long size = apk.length();
        if (size > Limits.MAX_ARCHIVE_FILE_BYTES * 8) {
            return Result.fail("APK_TOO_LARGE",
                    "APK больше допустимого для анализа размера: " + size + " байт");
        }
        ApkAnalysis.Builder builder = ApkAnalysis.builder(apk).sizeBytes(size);
        try (java.io.FileInputStream input = new java.io.FileInputStream(apk)) {
            builder.sha256(Hashing.sha256Hex(input, Long.MAX_VALUE));
        } catch (IOException e) {
            return Result.fail("APK_UNREADABLE", "Не удалось прочитать APK: " + e.getMessage());
        }

        List<String> entryNames = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                entryNames.add(name);
                if (entry.isDirectory()) {
                    continue;
                }
                classifyEntry(name, entry.getSize(), builder);
                if ("AndroidManifest.xml".equals(name)) {
                    Result<byte[]> manifestBytes = readEntry(zip, entry, MAX_MANIFEST_BYTES);
                    if (!manifestBytes.isOk()) {
                        return Result.fail(manifestBytes.errorCode(), manifestBytes.errorMessage());
                    }
                    Result<XmlDocument> manifest = BinaryXmlReader.parse(manifestBytes.value());
                    if (!manifest.isOk()) {
                        return Result.fail("APK_MANIFEST_UNREADABLE",
                                "AndroidManifest.xml не читается: " + manifest.errorCode()
                                        + " — " + manifest.errorMessage());
                    }
                    Result<Void> parsed = readManifest(manifest.value(), builder);
                    if (!parsed.isOk()) {
                        return Result.fail(parsed.errorCode(), parsed.errorMessage());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return Result.fail("APK_NOT_A_ZIP", "Файл не является корректным APK/ZIP: " + e.getMessage());
        }

        if (builder.build().packageId.isEmpty()) {
            return Result.fail("APK_NO_MANIFEST",
                    "В APK нет читаемого AndroidManifest.xml с package ID");
        }
        if (entryNames.contains(MARKER_ADAPTER_ASSET)) {
            builder.addMarker(MARKER_ADAPTER_ASSET);
        }
        String fileName = apk.getName().toLowerCase(java.util.Locale.ROOT);
        if (fileName.startsWith("split_") || fileName.contains(".split_config.")
                || fileName.startsWith("config.")) {
            builder.looksLikeSplit(true);
            builder.splitName(fileName);
        }
        return Result.ok(builder.build());
    }

    private static void classifyEntry(String name, long size, ApkAnalysis.Builder builder) {
        if (name.startsWith("lib/")) {
            String[] parts = name.split("/");
            if (parts.length >= 3) {
                builder.addAbi(parts[1]);
                builder.addNativeLibrary(name);
            }
            return;
        }
        if (name.endsWith(".dex")) {
            builder.addDex(name, Math.max(size, 0));
            return;
        }
        if ("resources.arsc".equals(name)) {
            builder.hasResourcesArsc(true);
            return;
        }
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC"))) {
            builder.addSignatureFile(name);
        }
    }

    private static Result<Void> readManifest(XmlDocument document, ApkAnalysis.Builder builder) {
        XmlElement root = document.root;
        if (!"manifest".equals(root.name)) {
            return Result.fail("APK_MANIFEST_ROOT",
                    "Корневой элемент manifest ожидается, найден: " + root.name);
        }
        String packageId = root.stringAttribute("package");
        if (packageId == null || packageId.isEmpty()) {
            return Result.fail("APK_NO_PACKAGE", "В manifest нет атрибута package");
        }
        builder.packageId(packageId);
        builder.versionName(root.stringAttribute("versionName"));
        Integer versionCode = root.intAttribute("versionCode");
        builder.versionCode(versionCode != null ? versionCode : 0L);
        String split = root.stringAttribute("split");
        if (split != null && !split.isEmpty()) {
            builder.splitName(split).looksLikeSplit(true);
        }

        XmlElement usesSdk = root.firstChild("uses-sdk");
        if (usesSdk != null) {
            builder.minSdkVersion(usesSdk.intAttribute("minSdkVersion"));
            builder.targetSdkVersion(usesSdk.intAttribute("targetSdkVersion"));
        }
        for (XmlElement permission : root.childrenNamed("uses-permission")) {
            String name = permission.stringAttribute("name");
            if (name != null) {
                builder.addUsesPermission(name);
            }
        }
        for (XmlElement permission : root.childrenNamed("uses-permission-sdk-23")) {
            String name = permission.stringAttribute("name");
            if (name != null) {
                builder.addUsesPermission(name);
            }
        }
        for (XmlElement permission : root.childrenNamed("permission")) {
            String name = permission.stringAttribute("name");
            XmlValue protection = permission.attribute("protectionLevel");
            builder.addDeclaredPermission(name + (protection != null
                    ? " (protectionLevel=" + protection.asString() + ')' : ""));
        }

        XmlElement application = root.firstChild("application");
        if (application != null) {
            String appName = application.stringAttribute("name");
            builder.applicationName(appName != null ? appName : "");
            // PAT-10: существующие providers, authorities и launcher-компоненты
            // обязательно учитываются, чтобы патч их не ломал.
            for (XmlElement provider : application.childrenNamed("provider")) {
                String authorities = provider.stringAttribute("authorities");
                String providerName = provider.stringAttribute("name");
                if (authorities != null) {
                    builder.addProviderAuthority(authorities);
                }
                if (MARKER_RECOVERY_ACTIVITY.equals(providerName)) {
                    builder.addMarker(providerName);
                }
            }
            for (XmlElement activity : application.childrenNamed("activity")) {
                String activityName = activity.stringAttribute("name");
                if (activityName != null) {
                    builder.addActivityName(activityName);
                }
                if (MARKER_RECOVERY_ACTIVITY.equals(activityName)) {
                    builder.addMarker(activityName);
                }
                // Launcher-компонент фиксируется отдельно: его нельзя ломать (PAT-10),
                // но маркером предыдущего патча он не является.
                for (XmlElement filter : activity.childrenNamed("intent-filter")) {
                    for (XmlElement action : filter.childrenNamed("action")) {
                        if ("android.intent.action.MAIN".equals(action.stringAttribute("name"))) {
                            builder.addLauncherActivity(activity.stringAttribute("name"));
                        }
                    }
                }
            }
        }
        return Result.ok(null);
    }

    private static Result<byte[]> readEntry(ZipFile zip, ZipEntry entry, long limit) {
        try (InputStream in = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    return Result.fail("APK_ENTRY_TOO_LARGE",
                            "Запись " + entry.getName() + " больше " + limit + " байт");
                }
                out.write(buffer, 0, read);
            }
            return Result.ok(out.toByteArray());
        } catch (IOException e) {
            return Result.fail("APK_ENTRY_UNREADABLE",
                    "Не удалось прочитать " + entry.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Проверяет, содержит ли DEX маркер runtime (PAT-11). Анализ идёт по байтам
     * записи: строковый пул DEX хранит имена типов в UTF-8.
     */
    public static boolean dexContainsRuntimeMarker(File apk) {
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".dex") || entry.getSize() > Limits.MAX_DEX_BYTES * 4) {
                    continue;
                }
                Result<byte[]> data = readEntry(zip, entry, Limits.MAX_DEX_BYTES * 4);
                if (data.isOk() && containsAscii(data.value(), MARKER_RUNTIME_CLASS)) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }

    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
