package dev.moduled.runtime;

import dev.moduled.core.CrashPolicy;

/**
 * Классификация причин завершения процесса (ТЗ §18.1 CR-05, CR-06).
 *
 * <p>На Android 11+ доступны {@code ApplicationExitInfo}; на Android 9–10
 * покрытие честнее ограничить Java uncaught exceptions, и это ограничение
 * показывается в диагностике, а не маскируется.</p>
 *
 * <p>Класс принимает целые коды причин, чтобы логика проверялась JVM-тестами без
 * Android. Числовые значения соответствуют константам
 * {@code android.app.ApplicationExitInfo.REASON_*}.</p>
 */
public final class ExitInfoClassifier {

    public static final int REASON_UNKNOWN = 0;
    public static final int REASON_EXIT_SELF = 1;
    public static final int REASON_SIGNALED = 2;
    public static final int REASON_LOW_MEMORY = 3;
    public static final int REASON_CRASH = 4;
    public static final int REASON_CRASH_NATIVE = 5;
    public static final int REASON_ANR = 6;
    public static final int REASON_INITIALIZATION_FAILURE = 7;
    public static final int REASON_PERMISSION_CHANGE = 8;
    public static final int REASON_EXCESSIVE_RESOURCE_USAGE = 9;
    public static final int REASON_USER_REQUESTED = 10;
    public static final int REASON_USER_STOPPED = 11;
    public static final int REASON_DEPENDENCY_DIED = 12;
    public static final int REASON_OTHER = 13;
    public static final int REASON_FREEZER = 14;
    public static final int REASON_PACKAGE_STATE_CHANGE = 15;
    public static final int REASON_PACKAGE_UPDATED = 16;

    private ExitInfoClassifier() {
    }

    /**
     * Java-краш ({@code REASON_CRASH}) — подтверждённый сбой. Native-краш тоже
     * считается сбоем процесса, но помечается отдельно: его не покрывает Java
     * uncaught handler (ТЗ §18.2).
     */
    public static CrashPolicy.EventType classify(int reason) {
        switch (reason) {
            case REASON_CRASH:
            case REASON_CRASH_NATIVE:
            case REASON_INITIALIZATION_FAILURE:
                return CrashPolicy.EventType.CRASH;
            case REASON_ANR:
                return CrashPolicy.EventType.ANR;
            case REASON_USER_REQUESTED:
            case REASON_USER_STOPPED:
                return CrashPolicy.EventType.FORCE_STOP;
            case REASON_LOW_MEMORY:
            case REASON_EXCESSIVE_RESOURCE_USAGE:
            case REASON_FREEZER:
                return CrashPolicy.EventType.LOW_MEMORY;
            case REASON_EXIT_SELF:
                return CrashPolicy.EventType.NORMAL_EXIT;
            default:
                return CrashPolicy.EventType.UNKNOWN;
        }
    }

    /**
     * Ключ для исключения повторного учёта одного exit event (CR-06).
     * Один и тот же отчёт может быть прочитан несколько раз между запусками.
     */
    public static String eventKey(long exitTimestampMs, int reason, int pid, String processName) {
        return "exit-" + exitTimestampMs + '-' + reason + '-' + pid + '-' + processName;
    }

    /**
     * Нужно ли учитывать событие: только основной процесс и только реальные
     * сбои/ANR влияют на политику, остальные события возвращают false, но всё
     * равно записываются в журнал как неизвестные/служебные.
     */
    public static boolean affectsCrashCounter(int reason) {
        CrashPolicy.EventType type = classify(reason);
        return type == CrashPolicy.EventType.CRASH || type == CrashPolicy.EventType.ANR;
    }

    /**
     * Честное описание покрытия crash-детекции для экрана диагностики (CR-06).
     *
     * @param sdkVersion уровень API устройства
     */
    public static String coverageDescription(int sdkVersion) {
        if (sdkVersion >= 30) {
            return "ApplicationExitInfo: учитываются краши, native-краши и ANR основного процесса "
                    + "за последние запуски; повторный учёт одного события исключён.";
        }
        return "Android 9–10: ApplicationExitInfo недоступен, поэтому учитываются только Java "
                + "uncaught exceptions в основном процессе. Покрытие сознательно уже и не включает "
                + "native-краши и ANR.";
    }
}
