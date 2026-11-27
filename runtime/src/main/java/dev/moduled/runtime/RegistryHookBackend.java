package dev.moduled.runtime;

import dev.moduled.api.HookCallback;
import dev.moduled.api.HookHandle;
import dev.moduled.api.HookResult;
import java.lang.reflect.Member;

/**
 * Backend на основе {@link InterceptionRegistry} для изолированного тестового
 * хоста (ТЗ §3.1). В реальном TikTok этот backend неприменим: там требуется
 * фактический ART-хук, проверяемый на устройстве (§9.1, этап 2).
 */
public final class RegistryHookBackend implements HookBackend {

    public static final String NAME = "demo-host-registry";

    private final InterceptionRegistry registry;

    public RegistryHookBackend(InterceptionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return registry != null;
    }

    @Override
    public HookResult hook(Member target, HookCallback callback) {
        if (registry == null) {
            return HookResult.failed(HookResult.ERR_BACKEND_UNAVAILABLE, "Registry is null");
        }
        if (target == null) {
            return HookResult.failed(HookResult.ERR_TARGET_NOT_FOUND, "Target member is null");
        }
        if (callback == null) {
            return HookResult.failed(HookResult.ERR_UNSUPPORTED_TARGET, "Callback is null");
        }
        String key = InterceptionRegistry.keyFor(target);
        try {
            HookHandle handle = registry.register(key, callback);
            return HookResult.hooked(handle);
        } catch (RuntimeException e) {
            return HookResult.failed(HookResult.ERR_BACKEND_FAILURE, e.toString());
        }
    }

    @Override
    public Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false,
                    classLoader != null ? classLoader : getClass().getClassLoader());
        } catch (ClassNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
