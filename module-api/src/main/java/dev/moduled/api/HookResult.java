package dev.moduled.api;

/**
 * Явный результат попытки перехвата (ТЗ §9.1). Отсутствие результата никогда не
 * интерпретируется как успешный хук.
 */
public final class HookResult {

    /** Коды отказов: единый словарь для всех backend. */
    public static final String ERR_BACKEND_UNAVAILABLE = "HOOK_BACKEND_UNAVAILABLE";
    public static final String ERR_TARGET_NOT_FOUND = "HOOK_TARGET_NOT_FOUND";
    public static final String ERR_UNSUPPORTED_TARGET = "HOOK_UNSUPPORTED_TARGET";
    public static final String ERR_BACKEND_FAILURE = "HOOK_BACKEND_FAILURE";
    public static final String ERR_ALREADY_UNHOOKED = "HOOK_ALREADY_UNHOOKED";

    private final boolean success;
    private final String errorCode;
    private final String message;
    private final HookHandle handle;

    private HookResult(boolean success, String errorCode, String message, HookHandle handle) {
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
        this.handle = handle;
    }

    public static HookResult hooked(HookHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("handle must not be null");
        }
        return new HookResult(true, null, null, handle);
    }

    public static HookResult failed(String errorCode, String message) {
        if (errorCode == null || errorCode.isEmpty()) {
            throw new IllegalArgumentException("errorCode must not be empty");
        }
        return new HookResult(false, errorCode, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    /** Код ошибки или null при успехе. */
    public String errorCode() {
        return errorCode;
    }

    /** Человекочитаемое объяснение для логов и экрана диагностики. */
    public String message() {
        return message;
    }

    /** Рукоятка хука; null при отказе. */
    public HookHandle handle() {
        if (!success) {
            throw new IllegalStateException("HookResult is a failure: " + errorCode);
        }
        return handle;
    }

    @Override
    public String toString() {
        return success ? "HookResult{hooked " + handle.targetDescription() + '}'
                : "HookResult{failed " + errorCode + ": " + message + '}';
    }
}
