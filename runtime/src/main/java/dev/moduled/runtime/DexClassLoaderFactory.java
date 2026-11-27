package dev.moduled.runtime;

import java.io.File;

/**
 * Фабрика ClassLoader'ов модуля. Вынесена в интерфейс, чтобы порядок загрузки и
 * обработка ошибок проверялись JVM-тестами без Android, а на устройстве
 * использовался {@link AndroidDexClassLoaderFactory}.
 */
public interface DexClassLoaderFactory {

    ClassLoader create(File dexFile, File optimizedDirectory, ClassLoader parent);
}
