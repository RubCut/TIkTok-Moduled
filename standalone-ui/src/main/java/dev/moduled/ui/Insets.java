package dev.moduled.ui;

import android.view.View;

/**
 * Системные отступы и edge-to-edge (ТЗ §21.3).
 */
public final class Insets {

    private Insets() {
    }

    /** Применяет отступы системных панелей к контейнеру экрана. */
    public static void applySystemBars(View content) {
        if (content == null) {
            return;
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
    }

    /** Отступ только снизу — для панелей с кнопками над клавиатурой. */
    public static void applyImePadding(View content) {
        if (content == null) {
            return;
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            androidx.core.graphics.Insets ime = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.ime());
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                    ime.bottom);
            return insets;
        });
    }
}
