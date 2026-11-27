package dev.moduled.api;

/**
 * Результат запроса программного перезапуска (ТЗ §19).
 *
 * <p>Перезапуск через загрузчик не является системным force stop и не
 * гарантирует остановку всех процессов. Частые запросы ограничиваются
 * платформой: это защита от бесконечного цикла, а не sandbox для DEX.</p>
 */
public final class RestartResult {

    public enum Status {
        /** Перезапуск согласован и запланирован; процесс хоста будет завершён. */
        SCHEDULED,
        /** Запрос отклонён ограничением частоты. */
        RATE_LIMITED,
        /** Перезапуск недоступен в этом процессе/состоянии (например, safe mode). */
        UNAVAILABLE,
        /** Пользователю нужно открыть приложение вручную: автозапуск запрещён ОС. */
        MANUAL_REQUIRED
    }

    private final Status status;
    private final String message;

    private RestartResult(Status status, String message) {
        this.status = status;
        this.message = message;
    }

    public static RestartResult of(Status status, String message) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        return new RestartResult(status, message);
    }

    public Status status() {
        return status;
    }

    /** Объяснение для пользователя или лога. */
    public String message() {
        return message;
    }

    public boolean isScheduled() {
        return status == Status.SCHEDULED;
    }

    @Override
    public String toString() {
        return "RestartResult{" + status + ": " + message + '}';
    }
}
