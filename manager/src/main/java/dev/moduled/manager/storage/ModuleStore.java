package dev.moduled.manager.storage;

import dev.moduled.core.ArchiveValidator;
import dev.moduled.core.AtomicFile;
import dev.moduled.core.ConfigValidator;
import dev.moduled.core.DexValidator;
import dev.moduled.core.Hashing;
import dev.moduled.core.ManifestValidator;
import dev.moduled.core.ModuleManifest;
import dev.moduled.core.ModulePaths;
import dev.moduled.core.Result;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Хранилище установленных модулей TModules (ТЗ §11, §20.3, PKG-05/PKG-06).
 *
 * <p>Установка атомарна: staging → проверка → публикация. При ошибке сохраняется
 * предыдущая рабочая версия и конфиг. Исполняемые файлы лежат во внутреннем
 * хранилище менеджера, а не в общей папке пользователя.</p>
 *
 * <p>Класс работает только с java.nio/java.io, поэтому проверяется JVM-тестами
 * без эмулятора.</p>
 */
public final class ModuleStore {

    private static final String STATE_FILE = "state.json";
    private static final String MANIFEST_FILE = "module.json";
    private static final String DEX_FILE = "classes.dex";
    private static final String CONFIG_FILE = "config.txt";
    private static final String KEPT_CONFIGS = ".kept-configs";
    private static final String STAGING_PREFIX = ".staging-";
    private static final String PREVIOUS_PREFIX = ".previous-";

    private final Path root;

    public ModuleStore(File root) {
        this.root = root.toPath().toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    // --- Установка ------------------------------------------------------------

    public Result<InstallOutcome> install(InstallRequest request) {
        if (request == null || request.archive == null) {
            return Result.fail("INSTALL_NO_INPUT", "Не указан файл пакета модуля");
        }
        if (!request.archive.isFile()) {
            return Result.fail("INSTALL_NO_INPUT",
                    "Файл пакета недоступен: " + request.archive);
        }

        Result<ArchiveValidator.ValidatedArchive> archive =
                ArchiveValidator.validate(request.archive.toPath());
        if (!archive.isOk()) {
            return Result.fail(archive.errorCode(), archive.errorMessage());
        }
        if (archive.value().entries.size() != 2
                || archive.value().get(MANIFEST_FILE) == null
                || archive.value().get(DEX_FILE) == null) {
            return Result.fail("PACKAGE_LAYOUT",
                    "Пакет v1 должен содержать ровно module.json и classes.dex в корне");
        }
        byte[] manifestBytes = archive.value().get(MANIFEST_FILE);
        byte[] dexBytes = archive.value().get(DEX_FILE);

        Result<ModuleManifest> manifestResult = ManifestValidator.validate(manifestBytes);
        if (!manifestResult.isOk()) {
            return Result.fail(manifestResult.errorCode(), manifestResult.errorMessage());
        }
        Result<Void> dexResult = DexValidator.validate(dexBytes);
        if (!dexResult.isOk()) {
            return Result.fail(dexResult.errorCode(), dexResult.errorMessage());
        }
        ModuleManifest manifest = manifestResult.value();
        String moduleId = manifest.id;

        Result<Path> targetPath = ModulePaths.moduleDir(root, moduleId);
        if (!targetPath.isOk()) {
            return Result.fail(targetPath.errorCode(), targetPath.errorMessage());
        }
        Path target = targetPath.value();
        boolean update = Files.isDirectory(target);

        ModuleState previousState = null;
        byte[] preservedConfig = null;
        if (update) {
            // PKG-06: замена существующего ID требует явного подтверждения.
            if (!request.confirmReplace) {
                return Result.fail("INSTALL_REQUIRES_CONFIRMATION",
                        "Модуль с ID " + moduleId + " уже установлен. ID сам по себе не доказывает "
                                + "того же автора: подтвердите замену или выберите другой пакет.");
            }
            previousState = readState(target);
            String previousFingerprint = previousState != null ? previousState.authorKeyFingerprint : "";
            if (!previousFingerprint.isEmpty() && !request.authorKeyFingerprint.isEmpty()
                    && !previousFingerprint.equalsIgnoreCase(request.authorKeyFingerprint)
                    && !request.confirmNewKey) {
                // §14.2: смена ключа требует подтверждённой ротации или нового согласия.
                return Result.fail("INSTALL_KEY_CHANGED",
                        "Авторский ключ отличается от сохранённого ("
                                + shortFingerprint(previousFingerprint) + " → "
                                + shortFingerprint(request.authorKeyFingerprint)
                                + "). Требуется явное согласие на новый ключ.");
            }
            Result<byte[]> config = readConfigBytes(target);
            if (config.isOk()) {
                // §13.3: обновление сохраняет конфиг; миграция формата — задача автора.
                preservedConfig = config.value();
            }
        }

        long timestamp = System.currentTimeMillis();
        Path staging = root.resolve(STAGING_PREFIX + moduleId + '-' + timestamp);
        try {
            Files.createDirectories(staging);
            Files.write(staging.resolve(MANIFEST_FILE), manifestBytes);
            Files.write(staging.resolve(DEX_FILE), dexBytes);
            byte[] configBytes = preservedConfig != null ? preservedConfig : new byte[0];
            if (!update) {
                Result<byte[]> kept = readKeptConfig(moduleId);
                if (kept.isOk() && kept.value().length > 0) {
                    // CFG-11: ранее сохранённый конфиг того же ID восстанавливается,
                    // и это показывается пользователю.
                    configBytes = kept.value();
                }
            }
            Result<Void> configCheck = ConfigValidator.validate(configBytes);
            if (!configCheck.isOk()) {
                return Result.fail(configCheck.errorCode(), configCheck.errorMessage());
            }
            Files.write(staging.resolve(CONFIG_FILE), configBytes);

            ModuleState state = previousState != null ? previousState.copy() : new ModuleState();
            state.origin = request.origin;
            state.sourceUrl = request.sourceUrl;
            state.archiveSha256 = request.archiveSha256.isEmpty()
                    ? hashFile(request.archive.toPath()) : request.archiveSha256;
            state.authorKeyFingerprint = request.authorKeyFingerprint;
            state.trustConfirmed = request.trustConfirmed;
            state.bundled = request.bundled;
            if (previousState == null) {
                state.installedAtMs = timestamp;
                // §11: новый сторонний модуль устанавливается выключенным до явного согласия.
                state.enabled = request.bundled && request.enableAfterInstall;
            } else {
                state.updatedAtMs = timestamp;
                if (request.enableAfterInstall) {
                    state.enabled = true;
                }
            }
            Result<Void> stateWritten = AtomicFile.write(staging.resolve(STATE_FILE),
                    ModuleState.encodeBytes(state));
            if (!stateWritten.isOk()) {
                return Result.fail(stateWritten.errorCode(), stateWritten.errorMessage());
            }

            // Проверка staging перед публикацией (PKG-05).
            Result<Void> staged = verifyStaged(staging, manifest, dexBytes);
            if (!staged.isOk()) {
                deleteQuietly(staging);
                return Result.fail(staged.errorCode(), staged.errorMessage());
            }

            Path previous = null;
            if (update) {
                previous = root.resolve(PREVIOUS_PREFIX + moduleId + '-' + timestamp);
                try {
                    Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException | RuntimeException e) {
                    Files.move(target, previous, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            try {
                Files.move(staging, target);
            } catch (IOException | RuntimeException e) {
                // Откат: возвращаем предыдущую рабочую версию.
                if (previous != null) {
                    try {
                        Files.move(previous, target);
                    } catch (IOException ignored) {
                        return Result.fail("INSTALL_ROLLBACK_FAILED",
                                "Публикация не удалась и откат не выполнен: " + e
                                        + "; предыдущая версия сохранена в " + previous);
                    }
                }
                deleteQuietly(staging);
                return Result.fail("INSTALL_PUBLISH_FAILED",
                        "Не удалось опубликовать модуль: " + e.getMessage());
            }
            if (previous != null) {
                prunePrevious(moduleId);
                deleteQuietly(previous);
            }
            if (!update) {
                deleteKeptConfig(moduleId);
            }
            Result<ModuleRecord> record = readRecord(moduleId);
            if (!record.isOk()) {
                return Result.fail(record.errorCode(), record.errorMessage());
            }
            boolean restored = !update && preservedConfig == null
                    && configBytes.length > 0;
            return Result.ok(new InstallOutcome(record.value(), update, restored,
                    record.value().state.enabled));
        } catch (IOException | RuntimeException e) {
            deleteQuietly(staging);
            return Result.fail("INSTALL_FAILED", "Установка не выполнена: " + e);
        }
    }

    private Result<Void> verifyStaged(Path staging, ModuleManifest manifest, byte[] expectedDex) {
        try {
            byte[] manifestBytes = Files.readAllBytes(staging.resolve(MANIFEST_FILE));
            Result<ModuleManifest> stagedManifest = ManifestValidator.validate(manifestBytes);
            if (!stagedManifest.isOk()) {
                return Result.fail("STAGING_MANIFEST_INVALID", stagedManifest.errorMessage());
            }
            if (!stagedManifest.value().id.equals(manifest.id)
                    || stagedManifest.value().versionCode != manifest.versionCode) {
                return Result.fail("STAGING_MISMATCH",
                        "Проверка staging не совпала с исходным манифестом");
            }
            byte[] dexBytes = Files.readAllBytes(staging.resolve(DEX_FILE));
            if (!Hashing.sha256Hex(dexBytes).equals(Hashing.sha256Hex(expectedDex))) {
                return Result.fail("STAGING_DEX_MISMATCH",
                        "Хеш DEX в staging не совпал с исходным пакетом");
            }
            Result<Void> dex = DexValidator.validate(dexBytes);
            if (!dex.isOk()) {
                return Result.fail(dex.errorCode(), dex.errorMessage());
            }
            Path stateFile = staging.resolve(STATE_FILE);
            if (!Files.isRegularFile(stateFile)) {
                return Result.fail("STAGING_STATE_MISSING", "В staging нет состояния модуля");
            }
            Path configFile = staging.resolve(CONFIG_FILE);
            if (!Files.isRegularFile(configFile)) {
                return Result.fail("STAGING_CONFIG_MISSING", "В staging нет файла конфига");
            }
            return Result.ok(null);
        } catch (IOException e) {
            return Result.fail("STAGING_UNREADABLE", "Staging не читается: " + e.getMessage());
        }
    }

    // --- Чтение и список ------------------------------------------------------

    public Result<List<ModuleRecord>> list() {
        List<ModuleRecord> records = new ArrayList<>();
        try {
            if (!Files.isDirectory(root)) {
                return Result.ok(records);
            }
            List<Path> candidates = new ArrayList<>();
            try (var stream = Files.list(root)) {
                stream.filter(Files::isDirectory).forEach(candidates::add);
            }
            candidates.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path dir : candidates) {
                String name = dir.getFileName().toString();
                if (name.startsWith(".")) {
                    continue; // staging, previous и сохранённые конфиги
                }
                Result<ModuleRecord> record = readRecordFrom(dir);
                if (record.isOk()) {
                    records.add(record.value());
                }
            }
        } catch (IOException e) {
            return Result.fail("STORE_UNREADABLE", "Хранилище модулей не читается: " + e.getMessage());
        }
        return Result.ok(records);
    }

    public Result<ModuleRecord> get(String moduleId) {
        Result<Path> path = ModulePaths.moduleDir(root, moduleId);
        if (!path.isOk()) {
            return Result.fail(path.errorCode(), path.errorMessage());
        }
        if (!Files.isDirectory(path.value())) {
            return Result.fail("MODULE_NOT_FOUND", "Модуль не установлен: " + moduleId);
        }
        return readRecordFrom(path.value());
    }

    private Result<ModuleRecord> readRecord(String moduleId) {
        Result<Path> path = ModulePaths.moduleDir(root, moduleId);
        if (!path.isOk()) {
            return Result.fail(path.errorCode(), path.errorMessage());
        }
        return readRecordFrom(path.value());
    }

    private Result<ModuleRecord> readRecordFrom(Path dir) {
        String moduleId = dir.getFileName().toString();
        Path manifestPath = dir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath)) {
            return Result.fail("MODULE_BROKEN", "Модуль " + moduleId + " без манифеста");
        }
        Result<ModuleManifest> manifest;
        try {
            manifest = ManifestValidator.validate(Files.readAllBytes(manifestPath));
        } catch (IOException e) {
            return Result.fail("MODULE_UNREADABLE", "Манифест не читается: " + e.getMessage());
        }
        if (!manifest.isOk()) {
            return Result.fail(manifest.errorCode(), manifest.errorMessage());
        }
        Path dexPath = dir.resolve(DEX_FILE);
        String dexHash = "";
        if (Files.isRegularFile(dexPath)) {
            try {
                dexHash = Hashing.sha256Hex(Files.readAllBytes(dexPath));
            } catch (IOException e) {
                return Result.fail("MODULE_UNREADABLE", "DEX не читается: " + e.getMessage());
            }
        }
        byte[] config = new byte[0];
        Path configPath = dir.resolve(CONFIG_FILE);
        if (Files.isRegularFile(configPath)) {
            try {
                config = Files.readAllBytes(configPath);
            } catch (IOException e) {
                return Result.fail("MODULE_UNREADABLE", "Конфиг не читается: " + e.getMessage());
            }
        }
        ModuleState state = readState(dir);
        return Result.ok(new ModuleRecord(manifest.value(),
                state != null ? state : new ModuleState(), dir.toFile(), dexHash,
                Hashing.sha256Hex(config), config.length));
    }

    private ModuleState readState(Path dir) {
        Path statePath = dir.resolve(STATE_FILE);
        if (!Files.isRegularFile(statePath)) {
            return null;
        }
        try {
            return ModuleState.decode(new String(Files.readAllBytes(statePath), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // Повреждённое состояние не интерпретируется как разрешение: модуль
            // считается выключенным (консервативное значение, ср. CR-08).
            ModuleState safe = new ModuleState();
            safe.enabled = false;
            return safe;
        }
    }

    // --- Состояние и конфиг ---------------------------------------------------

    public Result<Void> setEnabled(String moduleId, boolean enabled) {
        Result<Path> dir = ModulePaths.moduleDir(root, moduleId);
        if (!dir.isOk()) {
            return Result.fail(dir.errorCode(), dir.errorMessage());
        }
        ModuleState state = readState(dir.value());
        if (state == null) {
            return Result.fail("MODULE_NOT_FOUND", "Модуль не установлен: " + moduleId);
        }
        state.enabled = enabled;
        return AtomicFile.write(dir.value().resolve(STATE_FILE), ModuleState.encodeBytes(state));
    }

    public Result<Void> setTrustConfirmed(String moduleId, String fingerprint) {
        Result<Path> dir = ModulePaths.moduleDir(root, moduleId);
        if (!dir.isOk()) {
            return Result.fail(dir.errorCode(), dir.errorMessage());
        }
        ModuleState state = readState(dir.value());
        if (state == null) {
            return Result.fail("MODULE_NOT_FOUND", "Модуль не установлен: " + moduleId);
        }
        state.trustConfirmed = true;
        if (fingerprint != null && !fingerprint.isEmpty()) {
            state.authorKeyFingerprint = fingerprint;
        }
        return AtomicFile.write(dir.value().resolve(STATE_FILE), ModuleState.encodeBytes(state));
    }

    public Result<Void> markAppliedGeneration(String moduleId, long generation) {
        Result<Path> dir = ModulePaths.moduleDir(root, moduleId);
        if (!dir.isOk()) {
            return Result.fail(dir.errorCode(), dir.errorMessage());
        }
        ModuleState state = readState(dir.value());
        if (state == null) {
            return Result.fail("MODULE_NOT_FOUND", "Модуль не установлен: " + moduleId);
        }
        state.appliedGeneration = generation;
        return AtomicFile.write(dir.value().resolve(STATE_FILE), ModuleState.encodeBytes(state));
    }

    /** Точные байты конфига без преобразований (CFG-03, CFG-04). */
    public Result<byte[]> readConfig(String moduleId) {
        Result<Path> dir = ModulePaths.moduleDir(root, moduleId);
        if (!dir.isOk()) {
            return Result.fail(dir.errorCode(), dir.errorMessage());
        }
        return readConfigBytes(dir.value());
    }

    private Result<byte[]> readConfigBytes(Path dir) {
        Path configPath = dir.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(configPath)) {
            return Result.ok(new byte[0]);
        }
        try {
            byte[] data = Files.readAllBytes(configPath);
            Result<Void> valid = ConfigValidator.validate(data);
            if (!valid.isOk()) {
                return Result.fail(valid.errorCode(),
                        "Сохранённый конфиг повреждён: " + valid.errorMessage());
            }
            return Result.ok(data);
        } catch (IOException e) {
            return Result.fail("CONFIG_UNREADABLE", "Конфиг не читается: " + e.getMessage());
        }
    }

    /** Атомарное сохранение конфига (CFG-05, CFG-06): старый файл не теряется. */
    public Result<Void> writeConfig(String moduleId, byte[] data) {
        Result<Path> dir = ModulePaths.moduleDir(root, moduleId);
        if (!dir.isOk()) {
            return Result.fail(dir.errorCode(), dir.errorMessage());
        }
        if (!Files.isDirectory(dir.value())) {
            return Result.fail("MODULE_NOT_FOUND", "Модуль не установлен: " + moduleId);
        }
        Result<Void> valid = ConfigValidator.validate(data);
        if (!valid.isOk()) {
            return Result.fail(valid.errorCode(), valid.errorMessage());
        }
        return AtomicFile.write(dir.value().resolve(CONFIG_FILE), ConfigValidator.preserve(data));
    }

    // --- Удаление -------------------------------------------------------------

    /**
     * Удаление модуля (ТЗ §11). Безопасное значение по умолчанию — сохранить
     * конфиг: он остаётся в хранилище и предлагается при повторной установке
     * того же ID (CFG-11). Данные модуля внутри хоста менеджеру недоступны, и
     * это объясняется пользователю, а не имитируется.
     */
    public Result<Void> uninstall(String moduleId, boolean keepConfig) {
        Result<Path> dir = ModulePaths.moduleDir(root, moduleId);
        if (!dir.isOk()) {
            return Result.fail(dir.errorCode(), dir.errorMessage());
        }
        if (!Files.isDirectory(dir.value())) {
            return Result.fail("MODULE_NOT_FOUND", "Модуль не установлен: " + moduleId);
        }
        if (keepConfig) {
            Result<byte[]> config = readConfigBytes(dir.value());
            if (config.isOk() && config.value().length > 0) {
                Result<Void> saved = saveKeptConfig(moduleId, config.value());
                if (!saved.isOk()) {
                    return Result.fail(saved.errorCode(),
                            "Конфиг не удалось сохранить перед удалением: " + saved.errorMessage());
                }
            }
        }
        try {
            deleteRecursively(dir.value());
            return Result.ok(null);
        } catch (IOException e) {
            return Result.fail("UNINSTALL_FAILED", "Не удалось удалить модуль: " + e.getMessage());
        }
    }

    private Result<Void> saveKeptConfig(String moduleId, byte[] data) {
        Path kept = root.resolve(KEPT_CONFIGS).resolve(moduleId);
        try {
            Files.createDirectories(kept);
        } catch (IOException e) {
            return Result.fail("CONFIG_KEPT_FAILED", e.getMessage());
        }
        return AtomicFile.write(kept.resolve(CONFIG_FILE), data);
    }

    private Result<byte[]> readKeptConfig(String moduleId) {
        Path kept = root.resolve(KEPT_CONFIGS).resolve(moduleId).resolve(CONFIG_FILE);
        if (!Files.isRegularFile(kept)) {
            return Result.fail("CONFIG_NOT_KEPT", "Сохранённый конфиг отсутствует");
        }
        try {
            return Result.ok(Files.readAllBytes(kept));
        } catch (IOException e) {
            return Result.fail("CONFIG_UNREADABLE", e.getMessage());
        }
    }

    private void deleteKeptConfig(String moduleId) {
        deleteQuietly(root.resolve(KEPT_CONFIGS).resolve(moduleId));
    }

    public boolean hasKeptConfig(String moduleId) {
        return Files.isRegularFile(root.resolve(KEPT_CONFIGS).resolve(moduleId).resolve(CONFIG_FILE));
    }

    // --- Служебное ------------------------------------------------------------

    private void prunePrevious(String moduleId) {
        try (var stream = Files.list(root)) {
            List<Path> previous = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString()
                            .startsWith(PREVIOUS_PREFIX + moduleId + '-'))
                    .forEach(previous::add);
            previous.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (int i = 0; i < previous.size() - 1; i++) {
                deleteQuietly(previous.get(i));
            }
        } catch (IOException ignored) {
            // Очистка старых версий не критична для установки.
        }
    }

    /** Удаляет незавершённый staging при отказе или отмене (ТЗ §7.5). */
    public int cleanStaging() {
        int removed = 0;
        try {
            if (!Files.isDirectory(root)) {
                return 0;
            }
            try (var stream = Files.list(root)) {
                List<Path> stale = new ArrayList<>();
                stream.filter(p -> p.getFileName().toString().startsWith(STAGING_PREFIX))
                        .forEach(stale::add);
                for (Path path : stale) {
                    deleteRecursively(path);
                    removed++;
                }
            }
        } catch (IOException ignored) {
            // Очистка best effort.
        }
        return removed;
    }

    private static String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 16) {
            return "—";
        }
        return fingerprint.substring(0, 8) + "…" + fingerprint.substring(fingerprint.length() - 8);
    }

    private static String hashFile(Path path) {
        try {
            return Hashing.sha256Hex(Files.readAllBytes(path));
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException ignored) {
            // Очистка не должна ронять операцию.
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            List<Path> all = new ArrayList<>();
            stream.forEach(all::add);
            all.sort(Comparator.reverseOrder());
            for (Path item : all) {
                Files.deleteIfExists(item);
            }
        }
    }
}
