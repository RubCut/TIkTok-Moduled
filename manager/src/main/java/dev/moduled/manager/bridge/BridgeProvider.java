package dev.moduled.manager.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import dev.moduled.core.BridgeContract;
import dev.moduled.core.ModulePaths;
import dev.moduled.core.Result;
import dev.moduled.core.Snapshot;
import dev.moduled.manager.storage.ModuleRecord;
import dev.moduled.manager.storage.ModuleStore;
import dev.moduled.manager.storage.SnapshotStore;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Мост менеджера (ТЗ §20.1, §20.2).
 *
 * <ul>
 *   <li>Метаданные передаются Bundle'ом, файлы — дескрипторами только на чтение.</li>
 *   <li>Путь файла строится из валидированного ID модуля, а не из произвольной строки.</li>
 *   <li>Сетевые операции и тяжёлая обработка в этом пути отсутствуют.</li>
 *   <li>Снимок читается целиком из опубликованного поколения: читатель видит
 *       целиком старое или целиком новое состояние.</li>
 * </ul>
 */
public final class BridgeProvider extends ContentProvider {

    private ModuleStore modules;
    private SnapshotStore snapshots;

    @Override
    public boolean onCreate() {
        Context context = contextOrThrow();
        File storageRoot = new File(context.getFilesDir(), "tmoduled");
        modules = new ModuleStore(new File(storageRoot, "modules"));
        snapshots = new SnapshotStore(new File(storageRoot, "modules"));
        return true;
    }

    /**
     * Контекст провайдера или явный сбой. Унаследованный
     * {@code ContentProvider.requireContext()} здесь неприменим: он требует
     * API 30, а minSdk — 28 (NewApi).
     */
    private Context contextOrThrow() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("BridgeProvider has no context");
        }
        return context;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Result<String> caller = BridgeGuard.verifyCaller(contextOrThrow(), getCallingPackage());
        Bundle response = new Bundle();
        if (!caller.isOk()) {
            response.putBoolean(BridgeContract.KEY_OK, false);
            response.putString(BridgeContract.KEY_ERROR_CODE, caller.errorCode());
            response.putString(BridgeContract.KEY_MESSAGE, caller.errorMessage());
            return response;
        }
        response.putBoolean(BridgeContract.KEY_OK, true);
        if (method == null) {
            response.putBoolean(BridgeContract.KEY_OK, false);
            response.putString(BridgeContract.KEY_ERROR_CODE, "BRIDGE_NO_METHOD");
            return response;
        }
        switch (method) {
            case BridgeContract.METHOD_GET_SNAPSHOT:
                return fillSnapshot(response);
            case BridgeContract.METHOD_GET_MODULE_STATE:
                return fillModuleState(response, arg);
            case BridgeContract.METHOD_GET_DIAGNOSTICS:
                return fillDiagnostics(response, caller.value());
            case BridgeContract.METHOD_REPORT_EVENT:
                return recordEvent(response, extras);
            case BridgeContract.METHOD_REQUEST_RESTART:
                return reject(response, "RESTART_NOT_AVAILABLE_FROM_MANAGER",
                        "Перезапуск хоста выполняется самим runtime (§19): менеджер не может "
                                + "остановить чужой процесс без привилегий.");
            case BridgeContract.METHOD_RESET_SAFE_MODE:
                return reject(response, "SAFE_MODE_RESET_IN_HOST",
                        "Защёлка safe mode хранится в хосте: сброс выполняется на экране "
                                + "восстановления внутри приложения или после явного действия "
                                + "пользователя в нём (CR-07).");
            default:
                return reject(response, "BRIDGE_UNKNOWN_METHOD", "Неизвестный метод моста: " + method);
        }
    }

    private Bundle fillSnapshot(Bundle response) {
        Result<Snapshot> active = snapshots.active();
        if (!active.isOk()) {
            return reject(response, active.errorCode(),
                    "Снимок недоступен: " + active.errorMessage());
        }
        Snapshot snapshot = active.value();
        Result<String> json = snapshots.activeJson();
        response.putString(BridgeContract.KEY_SNAPSHOT_JSON, json.isOk() ? json.value() : "");
        response.putLong(BridgeContract.KEY_GENERATION, snapshot.generation);
        response.putLong(BridgeContract.KEY_CREATED_WALL_MS, snapshot.createdWallMs);
        // Возраст снимка виден runtime и UI: устаревание не скрывается (§5.2).
        response.putBoolean(BridgeContract.KEY_STALE,
                System.currentTimeMillis() - snapshot.createdWallMs > STALE_THRESHOLD_MS);
        return response;
    }

    private Bundle fillModuleState(Bundle response, String moduleId) {
        Result<String> id = BridgeGuard.verifyModuleId(moduleId);
        if (!id.isOk()) {
            return reject(response, id.errorCode(), id.errorMessage());
        }
        Result<ModuleRecord> record = modules.get(id.value());
        if (!record.isOk()) {
            return reject(response, record.errorCode(), record.errorMessage());
        }
        ModuleRecord module = record.value();
        response.putString(BridgeContract.KEY_MODULE_ID, module.id());
        response.putBoolean("enabled", module.state.enabled);
        response.putLong("versionCode", module.manifest.versionCode);
        response.putString("version", module.manifest.version);
        response.putLong("appliedGeneration", module.state.appliedGeneration);
        response.putLong("currentGeneration", snapshots.currentGeneration());
        return response;
    }

    private Bundle fillDiagnostics(Bundle response, String callingPackage) {
        response.putString("caller", callingPackage);
        response.putLong("generation", snapshots.currentGeneration());
        Result<List<ModuleRecord>> records = modules.list();
        ArrayList<String> summary = new ArrayList<>();
        if (records.isOk()) {
            for (ModuleRecord record : records.value()) {
                summary.add(record.id() + "=" + (record.state.enabled ? "enabled" : "disabled")
                        + ";v" + record.manifest.versionCode
                        + ";gen" + record.state.appliedGeneration);
            }
        } else {
            summary.add("store-error:" + records.errorCode());
        }
        response.putStringArrayList("modules", summary);
        return response;
    }

    private Bundle recordEvent(Bundle response, Bundle extras) {
        String eventType = extras != null ? extras.getString(BridgeContract.KEY_EVENT_TYPE) : null;
        String eventKey = extras != null ? extras.getString(BridgeContract.KEY_EVENT_KEY) : null;
        if (eventType == null || eventKey == null) {
            return reject(response, "BRIDGE_BAD_EVENT",
                    "Событие должно содержать тип и ключ, исключающий повторный учёт (CR-06).");
        }
        // Менеджер хранит копию события для экрана диагностики. Авторитетная
        // защёлка живёт в хосте: она работает и при недоступном менеджере.
        EventJournal.append(contextOrThrow(), eventType, eventKey);
        response.putString(BridgeContract.KEY_MESSAGE, "Событие принято в журнал менеджера");
        return response;
    }

    private Bundle reject(Bundle response, String code, String message) {
        response.putBoolean(BridgeContract.KEY_OK, false);
        response.putString(BridgeContract.KEY_ERROR_CODE, code);
        response.putString(BridgeContract.KEY_MESSAGE, message);
        return response;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Result<Void> readOnly = BridgeGuard.verifyReadOnly(mode);
        if (!readOnly.isOk()) {
            throw new FileNotFoundException(readOnly.errorMessage());
        }
        Result<String> caller = BridgeGuard.verifyCaller(contextOrThrow(), getCallingPackage());
        if (!caller.isOk()) {
            throw new FileNotFoundException(caller.errorCode() + ": " + caller.errorMessage());
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2) {
            throw new FileNotFoundException("Неподдерживаемый путь моста: " + uri);
        }
        String kind = segments.get(0);
        String parameter = segments.get(1);
        File storageRoot = new File(contextOrThrow().getFilesDir(), "tmoduled/modules");
        File target;
        switch (kind) {
            case BridgeContract.PATH_DEX: {
                Result<String> id = BridgeGuard.verifyModuleId(parameter);
                if (!id.isOk()) {
                    throw new FileNotFoundException(id.errorMessage());
                }
                Result<java.nio.file.Path> path = ModulePaths.dexFile(storageRoot.toPath(), id.value());
                if (!path.isOk()) {
                    throw new FileNotFoundException(path.errorMessage());
                }
                target = path.value().toFile();
                break;
            }
            case BridgeContract.PATH_CONFIG: {
                Result<String> id = BridgeGuard.verifyModuleId(parameter);
                if (!id.isOk()) {
                    throw new FileNotFoundException(id.errorMessage());
                }
                Result<java.nio.file.Path> path =
                        ModulePaths.configFile(storageRoot.toPath(), id.value());
                if (!path.isOk()) {
                    throw new FileNotFoundException(path.errorMessage());
                }
                target = path.value().toFile();
                if (!target.isFile()) {
                    // Пустой конфиг допустим (CFG-02): отдаём существующий пустой файл.
                    target = EmptyFileProvider.materialize(storageRoot, id.value());
                }
                break;
            }
            default:
                throw new FileNotFoundException("Неподдерживаемый ресурс моста: " + kind);
        }
        if (target == null || !target.isFile()) {
            throw new FileNotFoundException("Файл недоступен: " + uri);
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Порог «устарел» для снимка: 24 часа. */
    private static final long STALE_THRESHOLD_MS = 24L * 60 * 60 * 1000;

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        throw new UnsupportedOperationException("Мост не поддерживает query(): используйте call()");
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + BridgeContract.AUTHORITY + ".file";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Мост доступен только для чтения");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Мост доступен только для чтения");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Мост доступен только для чтения");
    }
}
