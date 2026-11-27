package dev.moduled.manager;

import android.app.Application;
import android.os.Process;
import dev.moduled.core.CrashReports;
import dev.moduled.manager.storage.SettingsStore;
import dev.moduled.ui.ThemeApplier;
import dev.moduled.ui.ThemeMode;
import java.io.File;

/**
 * Приложение TModules.
 *
 * <p>Собственные сбои менеджера — отдельный контур (ТЗ §18.4): обработчик
 * диагностики устанавливается до любого UI и не тянет MD3, чтобы отчёт
 * сохранялся даже при неработающем основном экране.</p>
 */
public final class TModulesApplication extends Application {

    public static final String PRODUCT = "TModules";
    public static final String VERSION = "0.1.0";

    private File storageRoot;
    private SettingsStore settings;
    private CrashReports crashReports;
    private Thread.UncaughtExceptionHandler crashHandler;
    private String startupError;

    @Override
    public void onCreate() {
        // Диагностика старта сохраняется первой: до темы и до экранов (§18.4).
        storageRoot = new File(getFilesDir(), "tmoduled");
        crashReports = new CrashReports(new File(storageRoot, "crash"), null);
        crashHandler = crashReports.install(PRODUCT, getPackageName(),
                Long.toString(Process.getStartElapsedRealtime()), false, "manager=" + VERSION);

        try {
            settings = new SettingsStore(new File(storageRoot, "settings.txt"));
            ThemeApplier.apply(this, ThemeMode.fromStorage(
                    settings.getString(SettingsStore.KEY_THEME, ThemeMode.SYSTEM.name())));
        } catch (Throwable t) {
            // Падение на старте менеджера не должно оставлять пользователя без
            // следа: ошибка сохраняется и показывается на экране диагностики.
            startupError = t.toString();
            crashReports.save(CrashReports.formatReport(PRODUCT, getPackageName(),
                    "startup", System.currentTimeMillis(), Thread.currentThread(), t, false,
                    "manager=" + VERSION));
        }
        super.onCreate();
    }

    public File storageRoot() {
        return storageRoot;
    }

    public File modulesRoot() {
        return new File(storageRoot, "modules");
    }

    public File repositoryRoot() {
        return new File(storageRoot, "repository");
    }

    public File downloadsRoot() {
        return new File(storageRoot, "downloads");
    }

    public File logsRoot() {
        return new File(storageRoot, "logs");
    }

    public SettingsStore settings() {
        if (settings == null) {
            settings = new SettingsStore(new File(storageRoot, "settings.txt"));
        }
        return settings;
    }

    public CrashReports crashReports() {
        return crashReports;
    }

    /**
     * Установленный обработчик сбоев: диагностика проверяет, не заменён ли он
     * хостом/другой библиотекой (§18.2, CR: проверка смены handler).
     */
    public Thread.UncaughtExceptionHandler crashHandler() {
        return crashHandler;
    }

    /** Ошибка старта менеджера — показывается на экране диагностики (§18.4). */
    public String startupError() {
        return startupError;
    }
}
