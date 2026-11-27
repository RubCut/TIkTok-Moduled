package dev.moduled.runtime;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import dev.moduled.api.RestartResult;

/**
 * Программный перезапуск хоста (ТЗ §19).
 *
 * <p>Порядок: сохранение состояния → согласование (ограничение частоты и safe
 * mode) → допустимое планирование запуска → завершение своего процесса. Это не
 * системный force stop и не гарантия остановки всех процессов. Фоновый запуск
 * Activity ограничен ОС, поэтому при отказе планирования возвращается
 * {@code MANUAL_REQUIRED} с инструкцией открыть приложение вручную.</p>
 *
 * <p>Перезапуск не сбрасывает счётчик сбоев и не обходит safe mode.</p>
 */
public final class RestartCoordinator {

    /** Задержка перед попыткой повторного запуска, мс. */
    private static final long RESTART_DELAY_MS = 600L;

    private final Context hostContext;
    private final RestartRateLimiter limiter;
    private final RuntimeLog log;
    private final boolean safeMode;

    public RestartCoordinator(Context hostContext, RestartRateLimiter limiter,
                              RuntimeLog log, boolean safeMode) {
        this.hostContext = hostContext;
        this.limiter = limiter;
        this.log = log;
        this.safeMode = safeMode;
    }

    public RestartResult requestRestart(String moduleId, String reason) {
        if (safeMode) {
            log.log(RuntimeEvent.RESTART_REJECTED, moduleId,
                    "Перезапуск отклонён: активен safe mode");
            return RestartResult.of(RestartResult.Status.UNAVAILABLE,
                    "Перезапуск недоступен в safe mode");
        }
        if (!limiter.allow(SystemClock.elapsedRealtime())) {
            log.log(RuntimeEvent.RESTART_REJECTED, moduleId,
                    "Перезапуск отклонён ограничением частоты (защита от цикла)");
            return RestartResult.of(RestartResult.Status.RATE_LIMITED,
                    "Слишком частые запросы перезапуска; попробуйте позже");
        }
        log.log(RuntimeEvent.RESTART_REQUESTED, moduleId, "Запрошен перезапуск: " + reason);

        RestartResult scheduled = scheduleRelaunch();
        if (scheduled.status() != RestartResult.Status.SCHEDULED) {
            return scheduled;
        }
        // Завершаем свой процесс: системный force stop недоступен без привилегий.
        Process.killProcess(Process.myPid());
        return scheduled;
    }

    private RestartResult scheduleRelaunch() {
        try {
            Intent launch = hostContext.getPackageManager()
                    .getLaunchIntentForPackage(hostContext.getPackageName());
            if (launch == null) {
                return RestartResult.of(RestartResult.Status.MANUAL_REQUIRED,
                        "Не удалось найти launcher-Intent: откройте приложение вручную");
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pending = PendingIntent.getActivity(
                    hostContext, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarm = (AlarmManager) hostContext.getSystemService(Context.ALARM_SERVICE);
            if (alarm == null) {
                return RestartResult.of(RestartResult.Status.MANUAL_REQUIRED,
                        "AlarmManager недоступен: откройте приложение вручную");
            }
            alarm.set(AlarmManager.RTC,
                    System.currentTimeMillis() + RESTART_DELAY_MS, pending);
            return RestartResult.of(RestartResult.Status.SCHEDULED,
                    "Перезапуск запланирован; при запрете ОС откройте приложение вручную");
        } catch (RuntimeException e) {
            return RestartResult.of(RestartResult.Status.MANUAL_REQUIRED,
                    "Автозапуск запрещён ОС (" + e.getMessage() + "): откройте приложение вручную");
        }
    }
}
