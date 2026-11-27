package dev.moduled.runtime.fixture;

import dev.moduled.api.HookCallback;
import dev.moduled.api.HookParam;
import dev.moduled.api.HookResult;
import dev.moduled.api.ModuleContext;
import dev.moduled.api.TModule;
import java.lang.reflect.Method;

/** Тестовый модуль: успешно стартует, читает конфиг и ставит один хук. */
public class FakeModule implements TModule {

    public static volatile String lastConfigText;
    public static volatile String lastModuleId;
    public static volatile String lastDataDir;
    public static volatile int hookCount;

    public static void reset() {
        lastConfigText = null;
        lastModuleId = null;
        lastDataDir = null;
        hookCount = 0;
    }

    @Override
    public void onLoad(ModuleContext context) throws Exception {
        lastModuleId = context.moduleId();
        lastConfigText = context.config().text();
        lastDataDir = context.dataDirectory() != null
                ? context.dataDirectory().getAbsolutePath() : null;

        Method target = InterceptedTarget.class.getMethod("greeting", String.class);
        HookResult result = context.hooks().hook(target, new HookCallback() {
            @Override
            public void before(HookParam param) {
                param.setResult("hooked:" + param.arg(0));
            }
        });
        if (result.isSuccess()) {
            hookCount++;
        }
    }

    /** Цель перехвата для кооперативного реестра тестового хоста. */
    public static final class InterceptedTarget {
        public String greeting(String name) {
            return "hello " + name;
        }
    }
}
