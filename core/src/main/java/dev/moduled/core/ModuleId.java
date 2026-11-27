package dev.moduled.core;

/**
 * Валидация ID модуля (ТЗ §10.1): до 128 ASCII-символов, буквы нижнего регистра,
 * цифры, точки и подчёркивания. ID никогда не используется как путь без отдельной
 * валидации — вызывающий код обязан проверять результат {@link #isValid(String)}.
 */
public final class ModuleId {
    private ModuleId() {
    }

    public static boolean isValid(String id) {
        if (id == null || id.isEmpty() || id.length() > Limits.MAX_MODULE_ID_LENGTH) {
            return false;
        }
        // Не начинаем и не заканчиваем точкой, нет пустых сегментов.
        if (id.charAt(0) == '.' || id.charAt(id.length() - 1) == '.') {
            return false;
        }
        if (id.contains("..")) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) {
                return false;
            }
        }
        // Первый символ — буква (чтобы ID не выглядел как число/путь).
        char first = id.charAt(0);
        return first >= 'a' && first <= 'z';
    }
}
