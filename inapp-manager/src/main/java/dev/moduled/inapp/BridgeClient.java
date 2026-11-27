package dev.moduled.inapp;

import android.content.Context;
import android.os.Bundle;

/**
 * Клиент моста менеджера (ТЗ §17.3, §20.1).
 *
 * <p>Источник состояния один и тот же: снимок менеджера через ContentProvider
 * {@code dev.moduled.manager.bridge}. Доступ возможен только при совпадении
 * подписи (signature-permission), поэтому при недоступном мосте модуль честно
 * переходит в read-only или сообщает ошибку — имитации состояния нет.</p>
 */
public final class BridgeClient {

    public static final String AUTHORITY = "dev.moduled.manager.bridge";
    public static final String METHOD_GET_SNAPSHOT = "getSnapshot";
    public static final String METHOD_GET_MODULE_STATE = "getModuleState";
    public static final String METHOD_GET_DIAGNOSTICS = "getDiagnostics";
    public static final String KEY_OK = "ok";
    public static final String KEY_ERROR_CODE = "errorCode";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_SNAPSHOT_JSON = "snapshotJson";
    public static final String KEY_GENERATION = "generation";
    public static final String KEY_STALE = "stale";

    /** Результат обращения к мосту: успех с данными или явная причина отказа. */
    public static final class BridgeResult {
        public final boolean available;
        public final boolean ok;
        public final String errorCode;
        public final String message;
        public final Bundle bundle;

        private BridgeResult(boolean available, boolean ok, String errorCode, String message,
                             Bundle bundle) {
            this.available = available;
            this.ok = ok;
            this.errorCode = errorCode;
            this.message = message;
            this.bundle = bundle;
        }

        static BridgeResult unavailable(String reason) {
            return new BridgeResult(false, false, "BRIDGE_UNAVAILABLE", reason, null);
        }

        static BridgeResult of(Bundle bundle) {
            if (bundle == null) {
                return new BridgeResult(true, false, "BRIDGE_NO_RESPONSE",
                        "Мост не ответил (provider вернул null)", null);
            }
            boolean ok = bundle.getBoolean(KEY_OK, false);
            return new BridgeResult(true, ok,
                    bundle.getString(KEY_ERROR_CODE, ""),
                    bundle.getString(KEY_MESSAGE, ""),
                    bundle);
        }
    }

    private final Context hostContext;

    public BridgeClient(Context hostContext) {
        this.hostContext = hostContext;
    }

    public BridgeResult getSnapshot() {
        return call(METHOD_GET_SNAPSHOT, null);
    }

    public BridgeResult getModuleState(String moduleId) {
        Bundle args = new Bundle();
        args.putString("moduleId", moduleId);
        return call(METHOD_GET_MODULE_STATE, args);
    }

    public BridgeResult getDiagnostics() {
        return call(METHOD_GET_DIAGNOSTICS, null);
    }

    private BridgeResult call(String method, Bundle args) {
        try {
            Bundle result = hostContext.getContentResolver()
                    .call(android.net.Uri.parse("content://" + AUTHORITY), method, null, args);
            return BridgeResult.of(result);
        } catch (SecurityException e) {
            return BridgeResult.unavailable("Нет доступа к мосту (подпись не совпадает "
                    + "или permission не выдан): " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return BridgeResult.unavailable("Мост не зарегистрирован (TModules не установлен "
                    + "или authority отсутствует): " + e.getMessage());
        } catch (Throwable t) {
            return BridgeResult.unavailable("Мост недоступен: " + t);
        }
    }
}
