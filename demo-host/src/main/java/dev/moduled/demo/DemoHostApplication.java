package dev.moduled.demo;

import android.app.Application;

/**
 * Application тестового хоста (ТЗ §26.2).
 *
 * <p>Существование собственного Application обязательно: патчер проверяет
 * сценарий «оригинальный класс сохраняется и получает колбэки» (PAT-08,
 * PAT-10) на реальном коде, а не на пустом manifest.</p>
 */
public final class DemoHostApplication extends Application {

    /** Флаг для UI: оригинальный Application получил attachBaseContext. */
    public static volatile boolean attached;
    public static volatile boolean created;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        attached = true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        created = true;
    }
}
