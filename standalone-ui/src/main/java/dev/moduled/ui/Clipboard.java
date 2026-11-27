package dev.moduled.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/**
 * Копирование в буфер (ТЗ §18.3). Возвращает фактический результат: обещать
 * копирование из фона или из завершившегося процесса нельзя.
 */
public final class Clipboard {

    private Clipboard() {
    }

    public static boolean copyText(Context context, String label, String text) {
        if (context == null || text == null) {
            return false;
        }
        try {
            ClipboardManager manager =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return false;
            }
            manager.setPrimaryClip(ClipData.newPlainText(label, text));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
