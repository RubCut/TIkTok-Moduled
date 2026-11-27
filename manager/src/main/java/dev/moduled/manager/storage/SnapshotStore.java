package dev.moduled.manager.storage;

import dev.moduled.core.AtomicFile;
import dev.moduled.core.Hashing;
import dev.moduled.core.ModulePaths;
import dev.moduled.core.Result;
import dev.moduled.core.Snapshot;
import dev.moduled.core.SnapshotCodec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Публикация согласованных снимков (ТЗ §5.2, §20.2).
 *
 * <p>Порядок: candidate → проверка → active (прежний active становится previous).
 * Читатель видит целиком старое или целиком новое поколение. При повреждении
 * нового поколения используется предыдущий целый снимок или безопасный отказ —
 * не частично проверенный код.</p>
 */
public final class SnapshotStore {

    private final Path root;

    public SnapshotStore(File storageRoot) {
        this.root = storageRoot.toPath().toAbsolutePath().normalize();
    }

    public Path snapshotsDir() {
        return ModulePaths.snapshotsDir(root);
    }

    public long currentGeneration() {
        Result<Snapshot> active = read(ModulePaths.activeSnapshot(root));
        return active.isOk() ? active.value().generation : 0L;
    }

    public Result<Snapshot> active() {
        Result<Snapshot> active = read(ModulePaths.activeSnapshot(root));
        if (active.isOk()) {
            return active;
        }
        // §20.2: повреждённое поколение не используется — пробуем предыдущее.
        Result<Snapshot> previous = read(ModulePaths.previousSnapshot(root));
        if (previous.isOk()) {
            return previous;
        }
        return active;
    }

    public Result<Snapshot> previous() {
        return read(ModulePaths.previousSnapshot(root));
    }

    /** Публикует снимок как новое поколение. */
    public Result<Long> publish(Snapshot snapshot) {
        if (snapshot == null) {
            return Result.fail("SNAPSHOT_NULL", "Снимок не указан");
        }
        if (snapshot.generation <= currentGeneration()) {
            return Result.fail("SNAPSHOT_GENERATION_NOT_NEWER",
                    "Поколение " + snapshot.generation + " не новее текущего "
                            + currentGeneration());
        }
        String json = SnapshotCodec.encode(snapshot);
        Result<Snapshot> verified = SnapshotCodec.decode(json);
        if (!verified.isOk()) {
            return Result.fail(verified.errorCode(),
                    "Снимок не прошёл проверку перед публикацией: " + verified.errorMessage());
        }
        for (Snapshot.ModuleEntry entry : snapshot.modules) {
            if (!Hashing.isSha256Hex(entry.dexSha256) || !Hashing.isSha256Hex(entry.configSha256)) {
                return Result.fail("SNAPSHOT_BAD_HASH",
                        "Снимок содержит невалидный хеш для модуля " + entry.id);
            }
        }
        Path candidate = ModulePaths.candidateSnapshot(root);
        Path active = ModulePaths.activeSnapshot(root);
        Path previous = ModulePaths.previousSnapshot(root);
        try {
            Files.createDirectories(candidate.getParent());
        } catch (IOException e) {
            return Result.fail("SNAPSHOT_DIR_FAILED",
                    "Не удалось создать каталог снимков: " + e.getMessage());
        }
        Result<Void> written = AtomicFile.write(candidate, json.getBytes(StandardCharsets.UTF_8));
        if (!written.isOk()) {
            return Result.fail(written.errorCode(), written.errorMessage());
        }
        Result<Snapshot> candidateCheck = read(candidate);
        if (!candidateCheck.isOk() || candidateCheck.value().generation != snapshot.generation) {
            return Result.fail("SNAPSHOT_CANDIDATE_INVALID",
                    "Кандидат не читается после записи: " + candidateCheck.errorCode());
        }
        try {
            if (Files.isRegularFile(active)) {
                Files.move(active, previous, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(candidate, active);
        } catch (IOException e) {
            // Откат: возвращаем previous в active, если active исчез.
            try {
                if (!Files.isRegularFile(active) && Files.isRegularFile(previous)) {
                    Files.copy(previous, active);
                }
            } catch (IOException ignored) {
                // Активный снимок останется отсутствующим: runtime честно сообщит об этом.
            }
            return Result.fail("SNAPSHOT_PUBLISH_FAILED",
                    "Публикация снимка не удалась: " + e.getMessage());
        }
        return Result.ok(snapshot.generation);
    }

    /** Собирает снимок следующего поколения из текущего состояния хранилища. */
    public Result<Snapshot> buildNext(ModuleStore modules) {
        Result<List<ModuleRecord>> records = modules.list();
        if (!records.isOk()) {
            return Result.fail(records.errorCode(), records.errorMessage());
        }
        List<Snapshot.ModuleEntry> entries = new ArrayList<>();
        for (ModuleRecord record : records.value()) {
            if (!record.state.enabled) {
                // Выключенный модуль попадает в снимок как неактивный: порядок и
                // состав снимка остаются полными и проверяемыми (RUN-10).
                entries.add(new Snapshot.ModuleEntry(record.id(), record.manifest.versionCode,
                        record.dexSha256, record.configSha256, false));
                continue;
            }
            if (record.dexSha256.isEmpty()) {
                return Result.fail("SNAPSHOT_MODULE_INCOMPLETE",
                        "Модуль " + record.id() + " без DEX не может попасть в снимок");
            }
            entries.add(new Snapshot.ModuleEntry(record.id(), record.manifest.versionCode,
                    record.dexSha256, record.configSha256, true));
        }
        long generation = currentGeneration() + 1;
        return Result.ok(new Snapshot(generation, System.currentTimeMillis(), entries));
    }

    private Result<Snapshot> read(Path path) {
        if (!Files.isRegularFile(path)) {
            return Result.fail("SNAPSHOT_MISSING", "Файл снимка отсутствует: " + path.getFileName());
        }
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return SnapshotCodec.decode(json);
        } catch (IOException e) {
            return Result.fail("SNAPSHOT_UNREADABLE", "Снимок не читается: " + e.getMessage());
        }
    }

    /** Текст активного снимка — для моста и диагностики. */
    public Result<String> activeJson() {
        Path active = ModulePaths.activeSnapshot(root);
        if (!Files.isRegularFile(active)) {
            return Result.fail("SNAPSHOT_MISSING", "Активный снимок отсутствует");
        }
        try {
            return Result.ok(new String(Files.readAllBytes(active), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Result.fail("SNAPSHOT_UNREADABLE", e.getMessage());
        }
    }
}
