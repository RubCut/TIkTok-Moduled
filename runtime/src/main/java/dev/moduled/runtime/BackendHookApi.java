package dev.moduled.runtime;

import dev.moduled.api.HookApi;
import dev.moduled.api.HookCallback;
import dev.moduled.api.HookResult;
import java.lang.reflect.Member;

/**
 * Реализация {@link HookApi}, выдаваемая модулю: добавляет журналирование и
 * явные коды ошибок вокруг выбранного backend (ТЗ §9.1, RUN-09).
 */
public final class BackendHookApi implements HookApi {

    private final HookBackend backend;
    private final RuntimeLog log;
    private final String moduleId;
    private int installedCount;

    public BackendHookApi(HookBackend backend, RuntimeLog log, String moduleId) {
        this.backend = backend;
        this.log = log;
        this.moduleId = moduleId;
    }

    @Override
    public String backendName() {
        return backend.name();
    }

    @Override
    public boolean isAvailable() {
        return backend.isAvailable();
    }

    public String unavailabilityReason() {
        return backend.unavailabilityReason();
    }

    @Override
    public HookResult hook(Member target, HookCallback callback) {
        HookResult result;
        try {
            result = backend.hook(target, callback);
        } catch (Throwable t) {
            result = HookResult.failed(HookResult.ERR_BACKEND_FAILURE, t.toString());
        }
        if (result.isSuccess()) {
            installedCount++;
            log.log(RuntimeEvent.HOOK_INSTALLED, moduleId,
                    "Hook установлен: " + result.handle().targetDescription()
                            + " (backend " + backend.name() + ')');
        } else {
            log.log(RuntimeEvent.HOOK_FAILED, moduleId,
                    "Hook не установлен: " + result.errorCode() + " — " + result.message());
        }
        return result;
    }

    /** Сколько хуков успешно установлено через этот API (RUN-08, диагностика). */
    public int installedCount() {
        return installedCount;
    }

    @Override
    public Class<?> findClass(String className) {
        return backend.findClass(className, null);
    }

    @Override
    public Class<?> findClass(String className, ClassLoader classLoader) {
        return backend.findClass(className, classLoader);
    }
}
