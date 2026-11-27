package dev.moduled.runtime;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import dev.moduled.core.CrashPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Сбор подтверждённых сбоев из {@code ApplicationExitInfo} (ТЗ §18.1 CR-06).
 *
 * <p>Доступно с Android 11 (API 30). На Android 9–10 метод возвращает пустой
 * список, и диагностика честно показывает более узкое покрытие — только Java
 * uncaught exceptions. Повторный учёт одного exit event исключается ключом
 * события на стороне {@link CrashPolicy}.</p>
 *
 * <p>Учитывается только основной процесс: запуск модулей во вспомогательных
 * процессах в прототипе не поддерживается (ТЗ §8.1).</p>
 */
public final class ApplicationExitInfoCollector {

    private static final int MAX_HISTORY = 32;

    private ApplicationExitInfoCollector() {
    }

    public static List<CrashPolicy.Event> collect(Context context, String mainProcessName,
                                                  long sinceWallMs) {
        List<CrashPolicy.Event> events = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return events;
        }
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return events;
        }
        List<ApplicationExitInfo> history;
        try {
            history = activityManager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, MAX_HISTORY);
        } catch (RuntimeException e) {
            return events;
        }
        if (history == null) {
            return events;
        }
        long monotonicNow = SystemClock.elapsedRealtime();
        long wallNow = System.currentTimeMillis();
        for (ApplicationExitInfo info : history) {
            if (info == null) {
                continue;
            }
            long exitWallMs = info.getTimestamp();
            if (exitWallMs < sinceWallMs) {
                continue;
            }
            String processName = info.getProcessName();
            if (mainProcessName != null && !mainProcessName.equals(processName)) {
                continue;
            }
            int reason = info.getReason();
            if (!ExitInfoClassifier.affectsCrashCounter(reason)) {
                continue;
            }
            // Перевод wall-времени события в монотонную шкалу политики сбоев.
            long monotonic = monotonicNow - Math.max(0, wallNow - exitWallMs);
            String key = ExitInfoClassifier.eventKey(exitWallMs, reason, info.getPid(),
                    processName != null ? processName : "-");
            events.add(new CrashPolicy.Event(key, ExitInfoClassifier.classify(reason),
                    monotonic, exitWallMs));
        }
        return events;
    }

    /** Честное описание покрытия детекции сбоев для экрана диагностики. */
    public static String coverageDescription() {
        return ExitInfoClassifier.coverageDescription(Build.VERSION.SDK_INT);
    }
}
