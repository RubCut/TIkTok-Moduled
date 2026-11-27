package dev.moduled.api;

import java.lang.reflect.Member;

/**
 * Java/ART hook API (ТЗ §9.1).
 *
 * <p>Перехват методов Java/ART — основной первый механизм, и его реальная
 * работоспособность определяется выбранным hook backend на конкретном
 * устройстве. API возвращает явный результат и код ошибки вместо «молчаливого
 * отсутствия эффекта»: если backend недоступен, модуль узнаёт об этом сразу.</p>
 *
 * <p>Подмена ресурсов, native inline hooks, JNI-перехваты и патчинг {@code .so}
 * — отдельные механизмы, они не следуют автоматически из наличия DEX.</p>
 */
public interface HookApi {

    /** Имя фактического backend, например {@code none}, {@code demo-host-bridge}. */
    String backendName();

    /** Доступен ли backend в этом процессе и на этой версии ОС. */
    boolean isAvailable();

    /**
     * Перехват метода или конструктора.
     *
     * @param target   метод или конструктор, обычно найденный через
     *                 {@link #findClass(String, ClassLoader)} и reflection хоста;
     * @param callback обработчики до и после вызова.
     * @return явный результат: успех с {@link HookHandle} или отказ с кодом ошибки.
     */
    HookResult hook(Member target, HookCallback callback);

    /** Поиск класса ClassLoader'ом хоста. */
    Class<?> findClass(String className);

    /** Поиск класса указанным ClassLoader'ом. */
    Class<?> findClass(String className, ClassLoader classLoader);
}
