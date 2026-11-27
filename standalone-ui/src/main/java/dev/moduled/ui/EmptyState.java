package dev.moduled.ui;

import android.view.View;
import android.widget.TextView;

/**
 * Пустые состояния и ошибки (ТЗ §6.1: «пустые состояния объясняют следующий шаг,
 * а не имитируют успешно настроенную систему»).
 */
public final class EmptyState {

    private EmptyState() {
    }

    /** Показывает состояние: заголовок, объяснение и необязательное действие. */
    public static void show(View container, TextView title, TextView body, TextView action,
                            String titleText, String bodyText, String actionText,
                            Runnable onAction) {
        if (container != null) {
            container.setVisibility(View.VISIBLE);
        }
        if (title != null) {
            title.setText(titleText);
        }
        if (body != null) {
            body.setText(bodyText);
            body.setVisibility(bodyText == null || bodyText.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (action != null) {
            if (actionText == null || actionText.isEmpty()) {
                action.setVisibility(View.GONE);
            } else {
                action.setVisibility(View.VISIBLE);
                action.setText(actionText);
                action.setOnClickListener(view -> {
                    if (onAction != null) {
                        onAction.run();
                    }
                });
            }
        }
    }

    public static void hide(View container) {
        if (container != null) {
            container.setVisibility(View.GONE);
        }
    }
}
