package dev.moduled.runtime;

import dev.moduled.api.ModuleLogger;

/**
 * Журнал модуля (ТЗ §22): каждое событие получает ID модуля и попадает в общий
 * runtime-журнал с ротацией.
 */
public final class RuntimeLogger implements ModuleLogger {

    private final RuntimeLog log;
    private final String moduleId;

    public RuntimeLogger(RuntimeLog log, String moduleId) {
        this.log = log;
        this.moduleId = moduleId;
    }

    @Override
    public void log(Level level, String message) {
        log.log(eventFor(level), moduleId, prefix(level) + message);
    }

    @Override
    public void log(Level level, String message, Throwable throwable) {
        log.log(eventFor(level), moduleId, prefix(level) + message, throwable);
    }

    private static String prefix(Level level) {
        return "[module:" + level.name().charAt(0) + "] ";
    }

    private static RuntimeEvent eventFor(Level level) {
        switch (level) {
            case ERROR:
                return RuntimeEvent.MODULE_ERROR;
            case WARN:
                return RuntimeEvent.MODULE_INCOMPATIBLE;
            case INFO:
                return RuntimeEvent.MODULE_STARTED;
            default:
                return RuntimeEvent.MODULE_LOADED;
        }
    }
}
