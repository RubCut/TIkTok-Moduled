package dev.moduled.runtime;

import android.content.Context;
import dalvik.system.DexClassLoader;
import java.io.File;

/**
 * Реальная фабрика на {@link DexClassLoader} (ТЗ §8 RUN-05).
 *
 * <p>Каждый модуль получает отдельный ClassLoader с родителем — ClassLoader'ом
 * хоста: так модуль видит классы приложения, но не подменяет общий API
 * дублирующими копиями. Файл DEX к моменту загрузки уже read-only (§14.2).</p>
 */
public final class AndroidDexClassLoaderFactory implements DexClassLoaderFactory {

    private final Context hostContext;

    public AndroidDexClassLoaderFactory(Context hostContext) {
        this.hostContext = hostContext;
    }

    @Override
    public ClassLoader create(File dexFile, File optimizedDirectory, ClassLoader parent) {
        return new DexClassLoader(
                dexFile.getAbsolutePath(),
                optimizedDirectory != null ? optimizedDirectory.getAbsolutePath() : null,
                null,
                parent != null ? parent : hostContext.getClassLoader());
    }
}
