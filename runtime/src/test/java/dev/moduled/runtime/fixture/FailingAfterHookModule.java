package dev.moduled.runtime.fixture;

import dev.moduled.api.HookCallback;
import dev.moduled.api.HookParam;
import dev.moduled.api.ModuleContext;
import dev.moduled.api.TModule;
import java.lang.reflect.Method;

/**
 * Тестовый модуль, который падает ПОСЛЕ установки хука: по RUN-08 это не
 * чистый откат, поэтому состояние обязано требовать перезапуска.
 */
public class FailingAfterHookModule implements TModule {
    @Override
    public void onLoad(ModuleContext context) throws Exception {
        Method target = FakeModule.InterceptedTarget.class.getMethod("greeting", String.class);
        context.hooks().hook(target, new HookCallback() {
            @Override
            public void before(HookParam param) {
                param.setResult("installed");
            }
        });
        throw new IllegalStateException("module exploded after hooks");
    }
}
