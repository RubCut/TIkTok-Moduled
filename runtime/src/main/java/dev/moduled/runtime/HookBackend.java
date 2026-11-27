package dev.moduled.runtime;

import dev.moduled.api.HookCallback;
import dev.moduled.api.HookResult;
import java.lang.reflect.Member;

/**
 * Абстракция hook backend (ТЗ §9.1, §27).
 *
 * <p>Перехват методов Java/ART требует конкретного backend, который проверяется
 * на устройстве и ABI. Смена backend не должна требовать переписывания UI или
 * модулей: контракт один, реализации разные.</p>
 */
public interface HookBackend {

    /** Короткое имя backend для логов, карточки модуля и матрицы поддержки. */
    String name();

    /** Доступен ли backend в текущем процессе и на текущей версии ОС. */
    boolean isAvailable();

    /** Почему backend недоступен — текст для диагностики (не может быть null, если !isAvailable). */
    default String unavailabilityReason() {
        return isAvailable() ? "" : "Backend " + name() + " is not available";
    }

    HookResult hook(Member target, HookCallback callback);

    Class<?> findClass(String className, ClassLoader classLoader);
}
