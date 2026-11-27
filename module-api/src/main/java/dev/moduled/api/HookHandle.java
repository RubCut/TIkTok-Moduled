package dev.moduled.api;

/**
 * Рукоятка установленного хука. {@link #unhook()} — best-effort: полный откат
 * всех последствий модуля платформа не гарантирует (ТЗ §3.2, RUN-08).
 */
public interface HookHandle {

    /** Описание перехваченной цели для логов и диагностики. */
    String targetDescription();

    /** Снять хук. Возвращает false, если backend не поддерживает снятие. */
    boolean unhook();
}
