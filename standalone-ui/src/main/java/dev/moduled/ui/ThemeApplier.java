package dev.moduled.ui;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;

/**
 * Применение темы ко всем самостоятельным приложениям платформы (ТЗ §21.1).
 * Вызывается из {@code Application#onCreate}; runtime и module-api этот класс
 * не используют.
 */
public final class ThemeApplier {

    private ThemeApplier() {
    }

    public static void apply(Application application, ThemeMode mode) {
        ThemeMode effective = mode != null ? mode : ThemeMode.SYSTEM;
        switch (effective) {
            case LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case SYSTEM:
            case DYNAMIC:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
        if (effective.allowsDynamicColors()) {
            // На Android 11 и ниже вызов ничего не делает: базовая палитра остаётся корректной.
            DynamicColors.applyToActivitiesIfAvailable(application);
        }
    }
}
