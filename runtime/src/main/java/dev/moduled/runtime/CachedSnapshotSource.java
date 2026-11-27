package dev.moduled.runtime;

import dev.moduled.core.AtomicFile;
import dev.moduled.core.Hashing;
import dev.moduled.core.ManifestValidator;
import dev.moduled.core.ModuleManifest;
import dev.moduled.core.ModulePaths;
import dev.moduled.core.Result;
import dev.moduled.core.Snapshot;
import dev.moduled.core.SnapshotCodec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Запасной источник: последний целый снимок из private storage хоста
 * (ТЗ §5.2: «при недоступном менеджере допускается последний целый снимок, но
 * UI и логи обязаны показывать его устаревание»).
 *
 * <p>Используются только файлы, уже скопированные в private storage и прошедшие
 * проверку хеша. Никакой сети и никакого исполнения DEX из общих каталогов.</p>
 */
public final class CachedSnapshotSource implements SnapshotSource {

    private final File storageRoot;
    private final RuntimeLog log;

    public CachedSnapshotSource(File storageRoot, RuntimeLog log) {
        this.storageRoot = storageRoot;
        this.log = log;
    }

    @Override
    public Result<SnapshotData> load(boolean safeMode) {
        File active = ModulePaths.activeSnapshot(storageRoot.toPath()).toFile();
        if (!active.isFile()) {
            log.log(RuntimeEvent.SNAPSHOT_UNAVAILABLE, null,
                    "Локальный запасной снимок отсутствует");
            return Result.fail("SNAPSHOT_NO_CACHE",
                    "Менеджер недоступен и локальный снимок не найден");
        }
        String json;
        try {
            json = new String(Files.readAllBytes(active.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Result.fail("SNAPSHOT_CACHE_UNREADABLE",
                    "Запасной снимок не читается: " + e.getMessage());
        }
        Result<Snapshot> decoded = SnapshotCodec.decode(json);
        if (!decoded.isOk()) {
            // §20.2: повреждённое поколение не используется — пробуем предыдущее.
            Result<SnapshotData> previous = loadPrevious(safeMode);
            if (previous.isOk()) {
                return previous;
            }
            return Result.fail(decoded.errorCode(),
                    "Запасной снимок повреждён: " + decoded.errorMessage());
        }
        Snapshot snapshot = decoded.value();
        List<ResolvedModule> modules = new ArrayList<>();
        if (!safeMode) {
            for (Snapshot.ModuleEntry entry : snapshot.activeSortedById()) {
                Result<ResolvedModule> prepared = prepareFromDisk(entry, snapshot.generation);
                if (prepared.isOk()) {
                    modules.add(prepared.value());
                } else {
                    log.log(RuntimeEvent.MODULE_ERROR, entry.id,
                            "Модуль из запасного снимка не загружен: "
                                    + prepared.errorCode() + " — " + prepared.errorMessage());
                }
            }
        }
        long age = System.currentTimeMillis() - snapshot.createdWallMs;
        log.log(RuntimeEvent.SNAPSHOT_STALE, null,
                "Использован запасной снимок поколения " + snapshot.generation
                        + ", возраст " + age + " ms");
        return Result.ok(new SnapshotData(snapshot, modules, true, System.currentTimeMillis(),
                "cache:" + active.getName(), safeMode));
    }

    private Result<SnapshotData> loadPrevious(boolean safeMode) {
        File previous = ModulePaths.previousSnapshot(storageRoot.toPath()).toFile();
        if (!previous.isFile()) {
            return Result.fail("SNAPSHOT_NO_PREVIOUS", "Предыдущий снимок отсутствует");
        }
        try {
            String json = new String(Files.readAllBytes(previous.toPath()), StandardCharsets.UTF_8);
            Result<Snapshot> decoded = SnapshotCodec.decode(json);
            if (!decoded.isOk()) {
                return Result.fail(decoded.errorCode(), decoded.errorMessage());
            }
            log.log(RuntimeEvent.SNAPSHOT_STALE, null,
                    "Активный снимок повреждён; использован предыдущий поколения "
                            + decoded.value().generation);
            return Result.ok(new SnapshotData(decoded.value(), new ArrayList<>(), true,
                    System.currentTimeMillis(), "cache:previous", safeMode));
        } catch (IOException e) {
            return Result.fail("SNAPSHOT_CACHE_UNREADABLE", e.getMessage());
        }
    }

    private Result<ResolvedModule> prepareFromDisk(Snapshot.ModuleEntry entry, long generation) {
        Result<java.nio.file.Path> dexPath = ModulePaths.dexFile(storageRoot.toPath(), entry.id);
        Result<java.nio.file.Path> configPath = ModulePaths.configFile(storageRoot.toPath(), entry.id);
        Result<java.nio.file.Path> manifestPath =
                ModulePaths.descriptorFile(storageRoot.toPath(), entry.id);
        if (!dexPath.isOk() || !configPath.isOk() || !manifestPath.isOk()) {
            return Result.fail("PATH_BAD_ID", "Недопустимый ID модуля: " + entry.id);
        }
        File dexFile = dexPath.value().toFile();
        if (!dexFile.isFile()) {
            return Result.fail("DEX_MISSING", "DEX модуля " + entry.id + " отсутствует локально");
        }
        byte[] dexBytes;
        try {
            dexBytes = Files.readAllBytes(dexFile.toPath());
        } catch (IOException e) {
            return Result.fail("DEX_UNREADABLE", "DEX не читается: " + e.getMessage());
        }
        String dexHash = Hashing.sha256Hex(dexBytes);
        if (!dexHash.equalsIgnoreCase(entry.dexSha256)) {
            return Result.fail("DEX_HASH_MISMATCH",
                    "Хеш локального DEX " + entry.id + " не совпал со снимком");
        }
        byte[] configBytes = new byte[0];
        File configFile = configPath.value().toFile();
        if (configFile.isFile()) {
            try {
                configBytes = Files.readAllBytes(configFile.toPath());
            } catch (IOException e) {
                return Result.fail("CONFIG_UNREADABLE",
                        "Конфиг " + entry.id + " не читается: " + e.getMessage());
            }
        }
        String configHash = Hashing.sha256Hex(configBytes);
        if (!configHash.equalsIgnoreCase(entry.configSha256)) {
            return Result.fail("CONFIG_HASH_MISMATCH",
                    "Хеш локального конфига " + entry.id + " не совпал со снимком");
        }
        Result<ModuleManifest> manifest;
        try {
            manifest = ManifestValidator.validate(Files.readAllBytes(manifestPath.value()));
        } catch (IOException e) {
            return Result.fail("MANIFEST_UNREADABLE", e.getMessage());
        }
        if (!manifest.isOk()) {
            return Result.fail(manifest.errorCode(), manifest.errorMessage());
        }
        if (manifest.value().versionCode != entry.versionCode) {
            return Result.fail("MODULE_VERSION_MISMATCH",
                    "Локальная версия " + entry.id + " не соответствует снимку");
        }
        return Result.ok(new ResolvedModule(manifest.value(), dexFile, entry.dexSha256,
                configBytes, entry.configSha256, generation));
    }

    /** Сохраняет снимок как запасной (используется менеджером при публикации). */
    public static Result<Void> persist(File storageRoot, String snapshotJson) {
        return AtomicFile.write(ModulePaths.activeSnapshot(storageRoot.toPath()),
                snapshotJson.getBytes(StandardCharsets.UTF_8));
    }
}
