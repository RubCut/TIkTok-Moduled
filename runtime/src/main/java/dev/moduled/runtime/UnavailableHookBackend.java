package dev.moduled.runtime;

import dev.moduled.api.HookCallback;
import dev.moduled.api.HookResult;
import java.lang.reflect.Member;

/**
 * Честный отказ вместо фиктивного успеха (ТЗ §9.1, §27 «несовместимый hook
 * backend»). Используется, когда реальный backend не встроен или не подтверждён
 * на этой версии хоста/ОС: модуль получает явный код ошибки и может сообщить
 * несовместимость, а не создавать видимость работы.
 */
public final class UnavailableHookBackend implements HookBackend {

    public static final String NAME = "none";

    private final String reason;

    public UnavailableHookBackend(String reason) {
        this.reason = reason;
    }

    public UnavailableHookBackend() {
        this("Hook backend не встроен в этот runtime или не подтверждён для данной версии хоста.");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String unavailabilityReason() {
        return reason;
    }

    @Override
    public HookResult hook(Member target, HookCallback callback) {
        return HookResult.failed(HookResult.ERR_BACKEND_UNAVAILABLE, reason);
    }

    @Override
    public Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false,
                    classLoader != null ? classLoader : getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
