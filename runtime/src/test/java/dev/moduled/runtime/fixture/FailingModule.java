package dev.moduled.runtime.fixture;

import dev.moduled.api.ModuleContext;
import dev.moduled.api.TModule;

/** Тестовый модуль, который падает до установки хуков. */
public class FailingModule implements TModule {
    @Override
    public void onLoad(ModuleContext context) {
        throw new IllegalStateException("module exploded before hooks");
    }
}
