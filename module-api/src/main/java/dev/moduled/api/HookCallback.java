package dev.moduled.api;

/**
 * Обработчики перехвата (ТЗ §9.1: передача аргументов, изменение результата и
 * обработка исключения должны иметь тесты).
 *
 * <p>Семантика нескольких хуков на одном методе определяется фактическим
 * backend; платформа не обещает произвольную приоритизацию.</p>
 */
public interface HookCallback {

    /**
     * Вызывается до оригинального метода. Чтобы не вызывать оригинал,
     * используйте {@link HookParam#setResult(Object)} или
     * {@link HookParam#setThrowable(Throwable)}.
     */
    default void before(HookParam param) throws Throwable {
    }

    /** Вызывается после оригинального метода или после перехваченного исключения. */
    default void after(HookParam param) throws Throwable {
    }
}
