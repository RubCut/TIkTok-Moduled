package dev.moduled.manager;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Фоновые операции и возврат на главный поток (ТЗ §23.1: никакого I/O,
 * распаковки и сети на UI-потоке).
 */
public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private AppExecutors() {
    }

    public static AppExecutors get() {
        return INSTANCE;
    }

    public void background(Runnable task) {
        background.execute(task);
    }

    public void main(Runnable task) {
        main.post(task);
    }

    /** Ограничение числа параллельных загрузок (§13.2). */
    public ExecutorService downloads() {
        return background;
    }
}
