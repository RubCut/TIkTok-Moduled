package dev.moduled.patcher;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import dev.moduled.core.Hashing;
import dev.moduled.core.Limits;
import dev.moduled.core.Result;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Патчер APK (ТЗ §7 PAT-01…PAT-11).
 *
 * <p>Порядок работы обязателен (§7.1):</p>
 * <ol>
 *   <li>предварительный анализ входа (PAT-01) — исходный APK не изменяется;</li>
 *   <li>план: профиль совместимости, точка запуска (PAT-08), разрешение
 *       конфликтов (PAT-06), распознавание повторного патча (PAT-11), проверка
 *       подписи ДО установки (PAT-07, §7.8);</li>
 *   <li>пересборка во временном каталоге: manifest правится точечно (PAT-09,
 *       PAT-10), добавляется runtime DEX и native hook backend только для
 *       выбранных ABI (PAT-05);</li>
 *   <li>подпись сохранённым ключом (PAT-07) и проверка результата;</li>
 *   <li>публикация готового файла только после всех проверок (атомарный move).</li>
 * </ol>
 */
public final class PatcherEngine {

    /** Имя записи runtime DEX в патченном APK (PAT-05). */
    public static final String ENTRY_RUNTIME_DEX = RuntimePayload.ENTRY_DEX;
    public static final String ENTRY_ADAPTER_JSON = RuntimePayload.ENTRY_ADAPTER;

    private final RuntimePayload payload;
    private final File workDirectory;

    public PatcherEngine(RuntimePayload payload, File workDirectory) {
        this.payload = payload;
        this.workDirectory = workDirectory;
    }

    /**
     * Строит план патча без изменения входного файла (PAT-01, PAT-02).
     *
     * @param apk            входной APK
     * @param profile        выбранный профиль совместимости (§5.1)
     * @param selectedAbis   ABI, выбранные пользователем (PAT-05)
     * @param installedSignatureFingerprint отпечаток подписи уже установленного
     *                       пакета (если известен) — конфликт подписи виден ДО
     *                       установки (PAT-07, §7.8)
     * @param newSignatureFingerprint        отпечаток ключа, которым будет подписан APK
     * @param requestedPackageId целевой package ID (смена — отдельный профиль, §7.8)
     */
    public Result<PatchPlan> plan(File apk, CompatibilityProfile profile,
                                  List<Integer> selectedAbis,
                                  String installedSignatureFingerprint,
                                  String newSignatureFingerprint,
                                  String requestedPackageId) {
        Result<ApkAnalysis> analyzed = ApkAnalyzer.analyze(apk);
        if (!analyzed.isOk()) {
            return Result.fail(analyzed.errorCode(), analyzed.errorMessage());
        }
        ApkAnalysis analysis = analyzed.value();
        PatchPlan.Builder plan = PatchPlan.builder(analysis, profile);

        if (profile != null) {
            List<String> mismatches = profile.mismatchReasons(analysis);
            for (String mismatch : mismatches) {
                plan.block("Профиль " + profile.id + " не подходит: " + mismatch);
            }
        }
        if (analysis.dexEntries.isEmpty()) {
            plan.block("В APK нет ни одного DEX: это не запускаемое приложение");
        }
        if (analysis.signatureFiles.isEmpty()) {
            plan.warn("Вход не подписан (нет META-INF/*.SF|.RSA|.DSA|.EC): "
                    + "возможно, это split или распакованный APK");
        }
        if (analysis.looksLikeSplit) {
            plan.block("Вход похож на split-APK (" + (analysis.splitName != null
                    ? analysis.splitName : apk.getName()) + "): патчится только базовый APK");
        }

        // Точка запуска — по фактическому анализу manifest (PAT-08).
        EntryPointAnalysis entryPoint = EntryPointAnalysis.from(analysis);
        plan.entryPoint(entryPoint);
        if (entryPoint.strategy == EntryPointAnalysis.Strategy.UNSUPPORTED) {
            plan.block("Точка запуска не определена: manifest не читается");
        }

        // Повторный патч (PAT-11).
        RepatchDetector.Verdict repatch = RepatchDetector.detect(analysis, apk);
        plan.repatch(repatch.repatched);
        if (repatch.repatched) {
            plan.warn("Вход уже изменён платформой. Патч будет повторным: "
                    + "маркеры " + repatch.markers);
        }

        // ABI: оставляем только выбранные и реально присутствующие (PAT-05).
        List<String> keep = AbiSupport.resolve(selectedAbis, analysis.abis);
        if (selectedAbis != null && !selectedAbis.isEmpty() && keep.isEmpty()) {
            plan.block("Выбранные ABI отсутствуют во входном APK: " + selectedAbis
                    + ", найдены " + analysis.abis);
        }
        if (keep.isEmpty()) {
            keep = new ArrayList<>(analysis.abis);
            plan.warn("ABI не выбраны: native hook backend берётся для всех найденных "
                    + keep + ". Лучше выбрать явно (PAT-05).");
        }
        for (String abi : keep) {
            plan.addKeptAbi(abi);
        }
        Result<Void> payloadCheck = payload.validate();
        if (!payloadCheck.isOk()) {
            plan.block(payloadCheck.errorCode() + ": " + payloadCheck.errorMessage());
        }
        List<String> missingNative = payload.missingNativeAbis(keep);
        if (!missingNative.isEmpty()) {
            // PAT-05: без native hook backend перехват методов недоступен —
            // это видно в плане и в диагностике, но патч не блокирует (§27).
            plan.warn("Нет native hook backend для ABI " + missingNative
                    + ": перехват методов будет недоступен, модули без хуков работают");
        }

        // Конфликты записей (PAT-06) — детерминированно, по имени. При повторном
        // патче (PAT-11) собственные записи платформы заменяются новой версией,
        // а не переименовываются: иначе в ZIP появились бы дубли.
        List<String> originalEntries = entryNamesOf(apk);
        List<String> addedEntries = payload.entryNames(keep);
        List<String> replacedEntries = new ArrayList<>();
        List<String> freshEntries = new ArrayList<>();
        for (String entry : addedEntries) {
            if (originalEntries.contains(entry)) {
                replacedEntries.add(entry);
            } else {
                freshEntries.add(entry);
            }
        }
        List<ConflictResolver.Conflict> conflicts =
                ConflictResolver.resolve(originalEntries, freshEntries, true);
        for (String entry : replacedEntries) {
            conflicts.add(ConflictResolver.replaced(entry));
        }
        Collections.sort(replacedEntries);
        plan.addConflicts(conflicts);
        if (ConflictResolver.hasBlocking(conflicts)) {
            for (ConflictResolver.Conflict conflict : conflicts) {
                if (conflict.decision == ConflictResolver.Decision.REJECT) {
                    plan.block("Неразрешимый конфликт: " + conflict);
                }
            }
        }

        // Исключение лишних ABI (PAT-05).
        List<String> excluded = AbiSupport.entriesToExclude(originalEntries, keep);
        Collections.sort(excluded);
        for (String entry : excluded) {
            plan.excludeEntry(entry);
        }
        for (String entry : addedEntries) {
            plan.addEntry(entry);
        }

        // Смена package ID — отдельный профиль (§7.8).
        if (requestedPackageId != null && !requestedPackageId.isEmpty()
                && !requestedPackageId.equals(analysis.packageId)) {
            ConflictResolver.Conflict packageConflict =
                    ConflictResolver.resolvePackageId(analysis.packageId, requestedPackageId);
            plan.addConflict(packageConflict);
            plan.block(packageConflict.reason);
        } else {
            plan.targetPackageId(analysis.packageId);
        }

        // Подпись (PAT-07, §7.8): конфликт показывается до установки.
        ConflictResolver.Conflict signatureConflict = ConflictResolver.resolveSignature(
                installedSignatureFingerprint, newSignatureFingerprint);
        plan.addConflict(signatureConflict);
        plan.signatureFingerprint(newSignatureFingerprint);
        plan.signatureKeyReused(installedSignatureFingerprint != null
                && installedSignatureFingerprint.equalsIgnoreCase(newSignatureFingerprint));
        if (signatureConflict.decision == ConflictResolver.Decision.REJECT) {
            plan.block(signatureConflict.reason);
        }
        return Result.ok(plan.build());
    }

    /**
     * Выполняет патч: пересборка во временном каталоге, подпись, проверка,
     * публикация (PAT-03, PAT-04, §7.1).
     *
     * @param plan        план, полученный из {@link #plan}
     * @param apk         исходный APK (не изменяется)
     * @param keyDirectory каталог внутреннего хранилища с ключом подписи
     * @param keyAlias    алиас ключа
     * @param outputName  имя готового файла (каталог публикации —
     *                    {@code /storage/emulated/0/tmoduled}, §20.4)
     * @param outputDirectory каталог публикации
     */
    public Result<PatchOutcome> patch(PatchPlan plan, File apk, File keyDirectory,
                                      String keyAlias, String outputName,
                                      File outputDirectory) {
        PatchOutcome.Builder outcome = PatchOutcome.builder()
                .packageId(plan.targetPackageId)
                .repatch(plan.repatch)
                .signatureFingerprint(plan.signatureFingerprint);
        outcome.addPlan(plan.toText());
        for (String warning : plan.warnings) {
            outcome.warn(warning);
        }
        for (String blocker : plan.blockers) {
            outcome.block(blocker);
        }
        if (!plan.canProceed()) {
            return Result.ok(outcome.build());
        }

        if (!workDirectory.isDirectory() && !workDirectory.mkdirs()) {
            return Result.fail("PATCH_NO_WORKDIR",
                    "Не удалось создать рабочий каталог: " + workDirectory);
        }
        File unsigned = new File(workDirectory, "unsigned-" + System.nanoTime() + ".apk");
        File signed = new File(workDirectory, "signed-" + System.nanoTime() + ".apk");
        try {
            Result<byte[]> manifestBytes = readManifest(apk);
            if (!manifestBytes.isOk()) {
                return Result.fail(manifestBytes.errorCode(), manifestBytes.errorMessage());
            }
            Result<ManifestEditor.EditResult> edit = ManifestEditor.apply(
                    manifestBytes.value(), plan.entryPoint.runtimeClassName,
                    ManifestEditor.SAFE_MODE_ACTIVITY);
            if (!edit.isOk()) {
                return Result.fail(edit.errorCode(), edit.errorMessage());
            }
            outcome.addManifestDiff(ManifestEditor.diffText(edit.value().changes));

            byte[] adapterJson = buildAdapterJson(plan);
            Result<Void> repacked = repack(apk, unsigned, edit.value().manifestBytes, plan,
                    adapterJson);
            if (!repacked.isOk()) {
                return Result.fail(repacked.errorCode(), repacked.errorMessage());
            }
            List<String> excludedAbiNames = new ArrayList<>();
            for (String excludedEntry : plan.excludedEntries) {
                // В отчёт попадают только ABI-каталоги, чтобы не раздувать список.
                if (excludedEntry.startsWith("lib/")) {
                    String[] parts = excludedEntry.split("/");
                    if (parts.length >= 2 && !excludedAbiNames.contains(parts[1])) {
                        excludedAbiNames.add(parts[1]);
                        outcome.addExcludedAbi(parts[1]);
                    }
                }
            }

            Result<SigningKeys.Loaded> key = SigningKeys.loadOrCreate(
                    keyDirectory, keyAlias, SigningProfile.Kind.DEVELOPMENT_SELF_SIGNED);
            if (!key.isOk()) {
                return Result.fail(key.errorCode(), key.errorMessage());
            }
            Result<Void> signResult = sign(unsigned, signed, key.value());
            if (!signResult.isOk()) {
                return Result.fail(signResult.errorCode(), signResult.errorMessage());
            }
            outcome.signatureFingerprint(key.value().fingerprint);

            Result<Void> verified = verify(signed, plan, edit.value().manifestBytes.length);
            if (!verified.isOk()) {
                return Result.fail(verified.errorCode(), verified.errorMessage());
            }

            if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
                return Result.fail("PATCH_NO_OUTDIR",
                        "Не удалось создать каталог публикации: " + outputDirectory);
            }
            File published = new File(outputDirectory, outputName);
            File tempPublished = new File(outputDirectory, outputName + ".tmp");
            Files.copy(signed.toPath(), tempPublished.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            // Публикация только после всех проверок (§7.1): атомарный move.
            try {
                Files.move(tempPublished.toPath(), published.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tempPublished.toPath(), published.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            String publishedHash;
            try (FileInputStream publishedInput = new FileInputStream(published)) {
                publishedHash = Hashing.sha256Hex(publishedInput, Long.MAX_VALUE);
            }
            outcome.output(published)
                    .sizeBytes(published.length())
                    .sha256(publishedHash);
            return Result.ok(outcome.build());
        } catch (IOException | RuntimeException e) {
            return Result.fail("PATCH_FAILED",
                    "Патч не удался: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            // Исходный APK не изменяется (§7.1), временные файлы удаляются.
            //noinspection ResultOfMethodCallIgnored
            unsigned.delete();
            //noinspection ResultOfMethodCallIgnored
            signed.delete();
        }
    }

    /**
     * Метаданные адаптера (PAT-08): runtime читает их из assets и честно
     * сообщает, подтверждена ли поддержка версии хоста. Оригинальный класс
     * Application сохраняется здесь же, чтобы RuntimeApplication делегировал
     * ему колбэки (PAT-10).
     */
    private byte[] buildAdapterJson(PatchPlan plan) {
        Map<String, Object> adapter = new LinkedHashMap<>();
        adapter.put("schemaVersion", 1);
        adapter.put("adapterName", payload.adapterName);
        adapter.put("adapterVersion", payload.runtimeVersion);
        adapter.put("runtimeApiVersion", payload.runtimeApiVersion);
        adapter.put("targetPackage", plan.targetPackageId);
        adapter.put("targetVersionName", plan.analysis.versionName);
        adapter.put("targetVersionCode", plan.analysis.versionCode);
        adapter.put("supported", plan.canProceed());
        adapter.put("keptAbis", new ArrayList<>(plan.keptAbis));
        adapter.put("originalApplication", plan.analysis.applicationName);
        adapter.put("entryPoint", plan.entryPoint != null
                ? plan.entryPoint.runtimeClassName : ManifestEditor.RUNTIME_APPLICATION);
        adapter.put("repatch", plan.repatch);
        return dev.moduled.core.JsonWriter.writeValue(adapter)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Result<Void> repack(File apk, File target, byte[] manifestBytes, PatchPlan plan,
                                byte[] adapterJson) {
        List<String> excluded = plan.excludedEntries;
        List<String> keptAbis = plan.keptAbis;
        try (ZipFile source = new ZipFile(apk);
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target))) {
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                // Прежняя подпись снимается: файл подписывается заново (PAT-07).
                if (isSignatureBlock(name)) {
                    continue;
                }
                if (excluded.contains(name)) {
                    continue;
                }
                if (plan.addedEntries.contains(name)) {
                    // Запись платформы заменяется новой версией (PAT-11).
                    continue;
                }
                byte[] content;
                if ("AndroidManifest.xml".equals(name)) {
                    content = manifestBytes;
                } else {
                    Result<byte[]> read = readEntry(source, entry, Limits.MAX_DOWNLOAD_BYTES);
                    if (!read.isOk()) {
                        return Result.fail(read.errorCode(), read.errorMessage());
                    }
                    content = read.value();
                }
                ZipEntry written = new ZipEntry(name);
                if (entry.getMethod() == ZipEntry.STORED) {
                    written.setMethod(ZipEntry.STORED);
                    written.setSize(content.length);
                    written.setCompressedSize(content.length);
                    CRC32 crc = new CRC32();
                    crc.update(content);
                    written.setCrc(crc.getValue());
                }
                if (entry.getTime() != -1) {
                    written.setTime(entry.getTime());
                }
                out.putNextEntry(written);
                out.write(content);
                out.closeEntry();
            }
            // Записи платформы добавляются в отсортированном порядке (PAT-06).
            List<String> added = payload.entryNames(keptAbis);
            Collections.sort(added);
            for (String entryName : added) {
                File file = payloadFile(entryName);
                boolean generated = ENTRY_ADAPTER_JSON.equals(entryName)
                        || RuntimePayload.ENTRY_MARKER.equals(entryName);
                if (!generated && (file == null || !file.isFile())) {
                    return Result.fail("PAYLOAD_MISSING",
                            "Файл полезной нагрузки отсутствует: " + entryName);
                }
                byte[] content;
                if (ENTRY_ADAPTER_JSON.equals(entryName)) {
                    content = adapterJson;
                } else if (RuntimePayload.ENTRY_MARKER.equals(entryName)) {
                    content = ("tmoduled-platform " + payload.runtimeVersion + '\n')
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else {
                    content = Files.readAllBytes(file.toPath());
                }
                ZipEntry written = new ZipEntry(entryName);
                if (entryName.endsWith(".dex") || entryName.endsWith(".so")) {
                    // DEX и native-библиотеки хранятся несжатыми: DexClassLoader
                    // на Android 8+ требует прямого доступа к байтам.
                    written.setMethod(ZipEntry.STORED);
                    written.setSize(content.length);
                    written.setCompressedSize(content.length);
                    CRC32 crc = new CRC32();
                    crc.update(content);
                    written.setCrc(crc.getValue());
                }
                out.putNextEntry(written);
                out.write(content);
                out.closeEntry();
            }
            out.finish();
            return Result.ok(null);
        } catch (IOException e) {
            return Result.fail("REPACK_FAILED", "Пересборка APK не удалась: " + e.getMessage());
        }
    }

    /**
     * Файл полезной нагрузки для записи. Для adapter.json и маркера контент
     * генерируется патчером, поэтому файл не требуется.
     */
    private File payloadFile(String entryName) {
        if (ENTRY_RUNTIME_DEX.equals(entryName)) {
            return payload.dexFile;
        }
        if (ENTRY_ADAPTER_JSON.equals(entryName) || RuntimePayload.ENTRY_MARKER.equals(entryName)) {
            return null;
        }
        if (entryName.startsWith("lib/") && entryName.endsWith(".so")) {
            String[] parts = entryName.split("/");
            return parts.length >= 2 ? payload.nativeLibraries.get(parts[1]) : null;
        }
        return null;
    }

    private Result<Void> sign(File input, File output, SigningKeys.Loaded key) {
        try {
            ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                    key.alias, key.privateKey,
                    Arrays.<X509Certificate>asList(key.certificate))
                    .build();
            ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                    .setInputApk(input)
                    .setOutputApk(output)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setAlignFileSize(true)
                    .build();
            signer.sign();
            return Result.ok(null);
        } catch (Exception e) {
            return Result.fail("SIGN_FAILED", "Подпись APK не удалась: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Result<Void> verify(File signed, PatchPlan plan, int manifestLength) {
        // 1. Подпись валидна (PAT-07).
        try {
            ApkVerifier verifier = new ApkVerifier.Builder(signed).build();
            ApkVerifier.Result result = verifier.verify();
            if (!result.isVerified()) {
                List<String> errors = new ArrayList<>();
                for (ApkVerifier.IssueWithParams issue : result.getErrors()) {
                    errors.add(issue.toString());
                }
                return Result.fail("VERIFY_SIGNATURE", "Подпись не прошла проверку: " + errors);
            }
        } catch (Exception e) {
            return Result.fail("VERIFY_SIGNATURE", "Проверка подписи не удалась: " + e.getMessage());
        }
        // 2. Manifest читается, package ID сохранён, launcher и authorities на месте (PAT-09, PAT-10).
        try (ZipFile zip = new ZipFile(signed)) {
            ZipEntry manifestEntry = zip.getEntry("AndroidManifest.xml");
            if (manifestEntry == null) {
                return Result.fail("VERIFY_NO_MANIFEST", "В патченном APK нет AndroidManifest.xml");
            }
            Result<byte[]> manifestBytes = readEntry(zip, manifestEntry, Limits.MAX_DOWNLOAD_BYTES);
            if (!manifestBytes.isOk()) {
                return Result.fail(manifestBytes.errorCode(), manifestBytes.errorMessage());
            }
            Result<dev.moduled.patcher.xml.XmlDocument> document =
                    dev.moduled.patcher.xml.BinaryXmlReader.parse(manifestBytes.value());
            if (!document.isOk()) {
                return Result.fail("VERIFY_MANIFEST",
                        "Manifest патченного APK не читается: " + document.errorCode());
            }
            dev.moduled.patcher.xml.XmlElement root = document.value().root;
            String packageId = root.stringAttribute("package");
            if (!plan.targetPackageId.equals(packageId)) {
                return Result.fail("VERIFY_PACKAGE_ID",
                        "package ID изменился: ожидался " + plan.targetPackageId
                                + ", найден " + packageId);
            }
            dev.moduled.patcher.xml.XmlElement application = root.firstChild("application");
            if (application == null) {
                return Result.fail("VERIFY_NO_APPLICATION", "В патченном manifest нет <application>");
            }
            String appName = application.stringAttribute("name");
            if (!plan.entryPoint.runtimeClassName.equals(appName)) {
                return Result.fail("VERIFY_ENTRY_POINT",
                        "Точка запуска не установлена: android:name=" + appName);
            }
            boolean recoveryDeclared = false;
            for (dev.moduled.patcher.xml.XmlElement activity
                    : application.childrenNamed("activity")) {
                if (ManifestEditor.SAFE_MODE_ACTIVITY.equals(activity.stringAttribute("name"))) {
                    recoveryDeclared = true;
                    break;
                }
            }
            if (!recoveryDeclared) {
                return Result.fail("VERIFY_RECOVERY_MISSING",
                        "Экран восстановления safe mode не объявлен в manifest");
            }
            // 3. Записи платформы присутствуют, исключённые ABI отсутствуют (PAT-05).
            for (String entry : plan.addedEntries) {
                if (zip.getEntry(entry) == null) {
                    return Result.fail("VERIFY_ENTRY_MISSING",
                            "Запись платформы не добавлена в APK: " + entry);
                }
            }
            for (String entry : plan.excludedEntries) {
                if (zip.getEntry(entry) != null) {
                    return Result.fail("VERIFY_ABI_NOT_EXCLUDED",
                            "Лишняя запись осталась в APK: " + entry);
                }
            }
            // 4. Оригинальные launcher-компоненты и authorities сохранены (PAT-10).
            List<String> authorities = new ArrayList<>();
            for (dev.moduled.patcher.xml.XmlElement provider
                    : application.descendantsNamed("provider")) {
                String value = provider.stringAttribute("authorities");
                if (value != null) {
                    authorities.add(value);
                }
            }
            for (String expected : plan.analysis.providerAuthorities) {
                if (!authorities.contains(expected)) {
                    return Result.fail("VERIFY_AUTHORITIES_LOST",
                            "Authority пропала из патченного manifest: " + expected);
                }
            }
            if (!plan.analysis.launcherActivities.isEmpty()) {
                List<String> activities = new ArrayList<>();
                for (dev.moduled.patcher.xml.XmlElement activity
                        : application.childrenNamed("activity")) {
                    String name = activity.stringAttribute("name");
                    if (name != null) {
                        activities.add(name);
                    }
                }
                for (String launcher : plan.analysis.launcherActivities) {
                    if (!activities.contains(launcher)) {
                        return Result.fail("VERIFY_LAUNCHER_LOST",
                                "Launcher-компонент пропал из патченного manifest: " + launcher);
                    }
                }
            }
        } catch (IOException e) {
            return Result.fail("VERIFY_IO", "Проверка патченного APK не удалась: " + e.getMessage());
        }
        if (signed.length() > Limits.MAX_DOWNLOAD_BYTES * 4) {
            return Result.fail("VERIFY_TOO_LARGE",
                    "Патченный APK больше допустимого размера: " + signed.length());
        }
        return Result.ok(null);
    }

    private static boolean isSignatureBlock(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    private static List<String> entryNamesOf(File apk) {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        } catch (IOException e) {
            return names;
        }
        return names;
    }

    private static Result<byte[]> readManifest(File apk) {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) {
                return Result.fail("APK_NO_MANIFEST", "В APK нет AndroidManifest.xml");
            }
            return readEntry(zip, entry, Limits.MAX_DOWNLOAD_BYTES);
        } catch (IOException e) {
            return Result.fail("APK_UNREADABLE", "APK не читается: " + e.getMessage());
        }
    }

    private static Result<byte[]> readEntry(ZipFile zip, ZipEntry entry, long limit) {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
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
}
