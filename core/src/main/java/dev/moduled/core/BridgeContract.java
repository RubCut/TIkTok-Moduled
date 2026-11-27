package dev.moduled.core;

/**
 * Контракт моста менеджер ↔ runtime (ТЗ §20.1, §20.2).
 *
 * <p>Authority и permission зафиксированы; permission имеет уровень
 * {@code signature}, поэтому доступ получает только приложение, подписанное тем
 * же сертификатом. Debug-хост и debug-менеджер используют общий сертификат;
 * production-модель подписи решается отдельно (ADR-005).</p>
 *
 * <p>Большие DEX и отчёты передаются файловыми дескрипторами, а не одним
 * Binder-payload: размер транзакции ограничен системой.</p>
 */
public final class BridgeContract {
    private BridgeContract() {
    }

    public static final String AUTHORITY = "dev.moduled.manager.bridge";
    public static final String PERMISSION = "dev.moduled.manager.permission.BRIDGE";
    public static final String PERMISSION_PROTECTION = "signature";

    /** Схема content-URI для потоковой передачи файлов. */
    public static final String URI_SCHEME = "content";

    /** Методы вызова через ContentProvider#call. */
    public static final String METHOD_GET_SNAPSHOT = "getSnapshot";
    public static final String METHOD_GET_DIAGNOSTICS = "getDiagnostics";
    public static final String METHOD_REPORT_EVENT = "reportEvent";
    public static final String METHOD_REQUEST_RESTART = "requestRestart";
    public static final String METHOD_RESET_SAFE_MODE = "resetSafeMode";
    public static final String METHOD_GET_MODULE_STATE = "getModuleState";

    /** Пути файлов для ContentProvider#openFile. */
    public static final String PATH_DEX = "dex";
    public static final String PATH_CONFIG = "config";
    public static final String PATH_REPORT = "report";

    /** Ключи Bundle. */
    public static final String KEY_SNAPSHOT_JSON = "snapshotJson";
    public static final String KEY_GENERATION = "generation";
    public static final String KEY_CREATED_WALL_MS = "createdWallMs";
    public static final String KEY_STALE = "stale";
    public static final String KEY_SAFE_MODE = "safeMode";
    public static final String KEY_MODULE_ID = "moduleId";
    public static final String KEY_EVENT_TYPE = "eventType";
    public static final String KEY_EVENT_KEY = "eventKey";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_ERROR_CODE = "errorCode";
    public static final String KEY_OK = "ok";
    public static final String KEY_REASON = "reason";
    public static final String KEY_RUNTIME_VERSION = "runtimeVersion";
    public static final String KEY_PROCESS_NAME = "processName";

    /** Ограничение одного Binder-payload: метаданные, не файлы. */
    public static final int MAX_METADATA_BYTES = 512 * 1024;

    /**
     * URI для чтения DEX модуля. ID модуля обязан быть предварительно
     * провалидирован: мост не выбирает произвольный файл из параметра (§20.2).
     */
    public static String dexUri(String moduleId) {
        requireValidId(moduleId);
        return URI_SCHEME + "://" + AUTHORITY + '/' + PATH_DEX + '/' + moduleId;
    }

    public static String configUri(String moduleId) {
        requireValidId(moduleId);
        return URI_SCHEME + "://" + AUTHORITY + '/' + PATH_CONFIG + '/' + moduleId;
    }

    public static String reportUri(String reportName) {
        if (reportName == null || reportName.isEmpty() || reportName.contains("/")
                || reportName.contains("..") || !reportName.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid report name: " + reportName);
        }
        return URI_SCHEME + "://" + AUTHORITY + '/' + PATH_REPORT + '/' + reportName;
    }

    private static void requireValidId(String moduleId) {
        if (!ModuleId.isValid(moduleId)) {
            throw new IllegalArgumentException("Invalid module id for bridge URI: " + moduleId);
        }
    }
}
