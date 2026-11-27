package dev.moduled.api;

/**
 * Журналирование модуля (ТЗ §9, §22). Каждое событие содержит время, уровень,
 * процесс, session ID, ID модуля и сообщение.
 *
 * <p>Платформа ограничивает объём журнала (до 5 MiB на процесс с ротацией) и по
 * умолчанию не пишет содержимое конфигов целиком. Гарантировать то же самое для
 * произвольного кода модуля платформа не может.</p>
 */
public interface ModuleLogger {

    enum Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    void log(Level level, String message);

    void log(Level level, String message, Throwable throwable);

    default void v(String message) {
        log(Level.VERBOSE, message);
    }

    default void d(String message) {
        log(Level.DEBUG, message);
    }

    default void i(String message) {
        log(Level.INFO, message);
    }

    default void w(String message) {
        log(Level.WARN, message);
    }

    default void e(String message) {
        log(Level.ERROR, message);
    }

    default void e(String message, Throwable throwable) {
        log(Level.ERROR, message, throwable);
    }
}
