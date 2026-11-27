package dev.moduled.runtime;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import dev.moduled.core.BridgeContract;
import dev.moduled.core.ConfigValidator;
import dev.moduled.core.Hashing;
import dev.moduled.core.ManifestValidator;
import dev.moduled.core.ModuleManifest;
import dev.moduled.core.ModulePaths;
import dev.moduled.core.Result;
import dev.moduled.core.Snapshot;
import dev.moduled.core.SnapshotCodec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Получение снимка через защищённый мост менеджера (ТЗ §20.1, §20.2).
 *
 * <p>Метаданные передаются Bundle'ом, а DEX и конфиги — файловыми
 * дескрипторами: большой Binder-payload запрещён. Каждый файл проверяется по
 * SHA-256 из снимка; несовпадение блокирует модуль, а не весь запуск (RUN-07).</p>
 *
 * <p>Сетевые операции в этом пути отсутствуют (RUN-03): мост читает локальное
 * хранилище менеджера.</p>
 */
public final class BridgeSnapshotSource implements SnapshotSource {

    private final Context hostContext;
    private final File storageRoot;
    private final RuntimeLog log;
    private final String hostPackage;
    private final String processName;

    public BridgeSnapshotSource(Context hostContext, File storageRoot, RuntimeLog log,
                                String hostPackage, String processName) {
        this.hostContext = hostContext;
        this.storageRoot = storageRoot;
        this.log = log;
        this.hostPackage = hostPackage;
        this.processName = processName;
    }

    @Override
    public Result<SnapshotData> load(boolean safeMode) {
        long startedAt = System.currentTimeMillis();
        log.log(RuntimeEvent.SNAPSHOT_REQUESTED, null,
                "Запрос снимка через мост " + BridgeContract.AUTHORITY);
        ContentResolver resolver = hostContext.getContentResolver();
        Bundle request = new Bundle();
        request.putString(BridgeContract.KEY_RUNTIME_VERSION, RuntimeVersion.VERSION);
        request.putString(BridgeContract.KEY_PROCESS_NAME, processName);

        Bundle response;
        try {
            response = resolver.call(Uri.parse("content://" + BridgeContract.AUTHORITY),
                    BridgeContract.METHOD_GET_SNAPSHOT, null, request);
        } catch (SecurityException e) {
            return Result.fail("BRIDGE_PERMISSION_DENIED",
                    "Мост отклонил вызов (нет signature-доступа): " + e.getMessage());
        } catch (RuntimeException e) {
            return Result.fail("BRIDGE_UNAVAILABLE",
                    "Менеджер недоступен: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (response == null) {
            return Result.fail("BRIDGE_EMPTY_RESPONSE", "Мост вернул пустой ответ");
        }
        String json = response.getString(BridgeContract.KEY_SNAPSHOT_JSON);
        Result<Snapshot> decoded = SnapshotCodec.decode(json);
        if (!decoded.isOk()) {
            log.log(RuntimeEvent.SNAPSHOT_REJECTED, null,
                    "Снимок отклонён: " + decoded.errorCode() + " — " + decoded.errorMessage());
            return Result.fail(decoded.errorCode(), decoded.errorMessage());
        }
        Snapshot snapshot = decoded.value();
        boolean stale = response.getBoolean(BridgeContract.KEY_STALE, false);

        List<ResolvedModule> modules = new ArrayList<>();
        for (Snapshot.ModuleEntry entry : snapshot.activeSortedById()) {
            if (safeMode) {
                break;
            }
            Result<ResolvedModule> prepared = prepare(entry, snapshot.generation);
            if (prepared.isOk()) {
                modules.add(prepared.value());
            } else {
                // RUN-07: ошибка одного пакета не останавливает остальные.
                log.log(RuntimeEvent.MODULE_ERROR, entry.id,
                        "Пакет не подготовлен: " + prepared.errorCode()
                                + " — " + prepared.errorMessage());
            }
        }

        cacheSnapshot(json, snapshot);
        long elapsed = System.currentTimeMillis() - startedAt;
        log.log(RuntimeEvent.SNAPSHOT_IMPORTED, null,
                "Снимок поколения " + snapshot.generation + " импортирован за " + elapsed
                        + " ms; модулей подготовлено: " + modules.size());
        if (stale) {
            log.log(RuntimeEvent.SNAPSHOT_STALE, null,
                    "Менеджер сообщил, что снимок устарел (возраст "
                            + (System.currentTimeMillis() - snapshot.createdWallMs) + " ms)");
        }
        return Result.ok(new SnapshotData(snapshot, modules, stale,
                System.currentTimeMillis(), "bridge:" + BridgeContract.AUTHORITY, safeMode));
    }

    private Result<ResolvedModule> prepare(Snapshot.ModuleEntry entry, long generation) {
        Result<File> dexResult = fetchDex(entry);
        if (!dexResult.isOk()) {
            return Result.fail(dexResult.errorCode(), dexResult.errorMessage());
        }
        Result<byte[]> configResult = fetchConfig(entry);
        if (!configResult.isOk()) {
            return Result.fail(configResult.errorCode(), configResult.errorMessage());
        }
        byte[] configBytes = configResult.value();
        Result<ModuleManifest> manifestResult = readManifest(entry.id);
        if (!manifestResult.isOk()) {
            return Result.fail(manifestResult.errorCode(), manifestResult.errorMessage());
        }
        ModuleManifest manifest = manifestResult.value();
        if (manifest.versionCode != entry.versionCode) {
            return Result.fail("MODULE_VERSION_MISMATCH",
                    "Манифест модуля " + entry.id + " имеет versionCode " + manifest.versionCode
                            + ", снимок ожидает " + entry.versionCode);
        }
        return Result.ok(new ResolvedModule(manifest, dexResult.value(), entry.dexSha256,
                configBytes, entry.configSha256, generation));
    }

    private Result<File> fetchDex(Snapshot.ModuleEntry entry) {
        Result<File> local = ModulePaths.dexFile(storageRoot.toPath(), entry.id)
                .isOk()
                ? Result.ok(ModulePaths.dexFile(storageRoot.toPath(), entry.id).value().toFile())
                : Result.fail("PATH_BAD_ID", "Недопустимый ID модуля: " + entry.id);
        if (!local.isOk()) {
            return local;
        }
        File existing = local.value();
        try {
            if (existing.isFile()) {
                byte[] data = Files.readAllBytes(existing.toPath());
                if (Hashing.sha256Hex(data).equalsIgnoreCase(entry.dexSha256)) {
                    log.log(RuntimeEvent.MODULE_COPIED_TO_SNAPSHOT, entry.id,
                            "DEX уже в private storage, хеш совпал");
                    return Result.ok(existing);
                }
                // Файл остался от прошлой версии: делаем его записываемым для замены.
                //noinspection ResultOfMethodCallIgnored
                existing.setWritable(true, true);
            }
        } catch (IOException e) {
            log.log(RuntimeEvent.MODULE_ERROR, entry.id,
                    "Не удалось прочитать локальный DEX: " + e.getMessage());
        }

        ContentResolver resolver = hostContext.getContentResolver();
        Uri uri = Uri.parse(BridgeContract.dexUri(entry.id));
        try (android.content.res.AssetFileDescriptor descriptor =
                     resolver.openAssetFileDescriptor(uri, "r")) {
            if (descriptor == null) {
                return Result.fail("BRIDGE_NO_DEX", "Мост не отдал DEX для " + entry.id);
            }
            FileDescriptor fd = descriptor.getFileDescriptor();
            try (InputStream in = new FileInputStream(fd)) {
                DexImporter importer = new DexImporter(storageRoot);
                return importer.importDex(entry.id, entry.dexSha256, in);
            }
        } catch (SecurityException e) {
            return Result.fail("BRIDGE_PERMISSION_DENIED",
                    "Мост отклонил чтение DEX " + entry.id + ": " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            return Result.fail("BRIDGE_DEX_FAILED",
                    "Не удалось получить DEX " + entry.id + ": " + e);
        }
    }

    private Result<byte[]> fetchConfig(Snapshot.ModuleEntry entry) {
        ContentResolver resolver = hostContext.getContentResolver();
        Uri uri = Uri.parse(BridgeContract.configUri(entry.id));
        byte[] data;
        try (android.content.res.AssetFileDescriptor descriptor =
                     resolver.openAssetFileDescriptor(uri, "r")) {
            if (descriptor == null) {
                // Отсутствие конфига допустимо: пустой файл валиден (CFG-02).
                data = new byte[0];
            } else {
                try (InputStream in = new FileInputStream(descriptor.getFileDescriptor())) {
                    data = readLimited(in, dev.moduled.core.Limits.MAX_CONFIG_BYTES);
                }
            }
        } catch (SecurityException e) {
            return Result.fail("BRIDGE_PERMISSION_DENIED",
                    "Мост отклонил чтение конфига " + entry.id + ": " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            // Конфиг мог отсутствовать у модуля, который его не использует.
            data = new byte[0];
        }
        Result<Void> valid = ConfigValidator.validate(data);
        if (!valid.isOk()) {
            return Result.fail(valid.errorCode(),
                    "Конфиг " + entry.id + " отклонён: " + valid.errorMessage());
        }
        String actualHash = Hashing.sha256Hex(data);
        if (!actualHash.equalsIgnoreCase(entry.configSha256)) {
            return Result.fail("CONFIG_HASH_MISMATCH",
                    "Хеш конфига " + entry.id + " не совпал со снимком: ожидался "
                            + entry.configSha256 + ", получен " + actualHash);
        }
        return Result.ok(data);
    }

    private Result<ModuleManifest> readManifest(String moduleId) {
        Result<java.nio.file.Path> path = ModulePaths.descriptorFile(storageRoot.toPath(), moduleId);
        if (!path.isOk()) {
            return Result.fail(path.errorCode(), path.errorMessage());
        }
        File file = path.value().toFile();
        if (!file.isFile()) {
            return Result.fail("MANIFEST_MISSING",
                    "Манифест модуля " + moduleId + " отсутствует в private storage");
        }
        try {
            return ManifestValidator.validate(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            return Result.fail("MANIFEST_UNREADABLE",
                    "Не удалось прочитать манифест " + moduleId + ": " + e.getMessage());
        }
    }

    private void cacheSnapshot(String json, Snapshot snapshot) {
        try {
            java.nio.file.Path active = ModulePaths.activeSnapshot(storageRoot.toPath());
            dev.moduled.core.AtomicFile.write(active, json.getBytes(StandardCharsets.UTF_8));
            log.log(RuntimeEvent.SNAPSHOT_IMPORTED, null,
                    "Снимок поколения " + snapshot.generation + " сохранён локально как запасной");
        } catch (RuntimeException e) {
            log.log(RuntimeEvent.SNAPSHOT_REJECTED, null,
                    "Не удалось сохранить запасной снимок: " + e.getMessage());
        }
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("Config exceeds " + limit + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
