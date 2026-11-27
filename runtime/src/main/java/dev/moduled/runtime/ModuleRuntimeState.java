package dev.moduled.runtime;

/**
 * Состояние модуля в текущем процессе (ТЗ §8 RUN-09, §21.2 экран диагностики).
 * В логах и UI различаются все перечисленные состояния — они не сводятся к
 * «работает / не работает».
 */
public final class ModuleRuntimeState {

    public enum State {
        /** Модуль присутствует в снимке и выбран к загрузке. */
        SELECTED,
        /** DEX скопирован и проверен в private storage. */
        COPIED,
        /** ClassLoader создан, класс точки входа найден. */
        LOADED,
        /** onLoad() завершился без исключения. */
        STARTED,
        /** Ошибка на любой фазе. */
        ERROR,
        /** Заблокирован safe mode (CR-03). */
        BLOCKED_SAFE_MODE,
        /** Несовместим: apiVersion или targetPackages. */
        INCOMPATIBLE
    }

    public final String moduleId;
    public final State state;
    public final String message;
    /** True, если исключение возникло после установки хуков (RUN-08). */
    public final boolean needsRestart;
    public final int hooksInstalled;

    public ModuleRuntimeState(String moduleId, State state, String message,
                              boolean needsRestart, int hooksInstalled) {
        this.moduleId = moduleId;
        this.state = state;
        this.message = message;
        this.needsRestart = needsRestart;
        this.hooksInstalled = hooksInstalled;
    }

    public static ModuleRuntimeState of(String moduleId, State state, String message) {
        return new ModuleRuntimeState(moduleId, state, message, false, 0);
    }

    public boolean isRunnable() {
        return state == State.STARTED;
    }

    @Override
    public String toString() {
        return moduleId + ": " + state + (message != null ? " (" + message + ')' : "");
    }
}
