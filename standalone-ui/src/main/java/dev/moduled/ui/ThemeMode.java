package dev.moduled.ui;

/** Режим темы (ТЗ §21.1: светлая и тёмная обязательны, динамические цвета — где поддерживаются). */
public enum ThemeMode {
    /** Следовать системной теме, без динамических цветов. */
    SYSTEM,
    LIGHT,
    DARK,
    /** Системная тема плюс динамические цвета на Android 12+. */
    DYNAMIC;

    public static ThemeMode fromStorage(String value) {
        if (value == null) {
            return SYSTEM;
        }
        try {
            return ThemeMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SYSTEM;
        }
    }

    public boolean allowsDynamicColors() {
        return this == DYNAMIC;
    }
}
