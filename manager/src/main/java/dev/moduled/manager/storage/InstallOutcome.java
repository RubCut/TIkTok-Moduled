package dev.moduled.manager.storage;

/** Результат установки: что произошло на самом деле (ТЗ §6.3, §11, CFG-11). */
public final class InstallOutcome {
    public final ModuleRecord record;
    /** Установка поверх существующего ID (PKG-06). */
    public final boolean update;
    /** Восстановлен ранее сохранённый конфиг того же ID (CFG-11). */
    public final boolean configRestored;
    /** Модуль включён сразу; иначе он выключен до явного согласия (§11). */
    public final boolean enabled;

    public InstallOutcome(ModuleRecord record, boolean update, boolean configRestored,
                          boolean enabled) {
        this.record = record;
        this.update = update;
        this.configRestored = configRestored;
        this.enabled = enabled;
    }
}
