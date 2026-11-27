package dev.moduled.runtime;

import dev.moduled.core.ModuleManifest;
import java.io.File;

/**
 * Модуль, подготовленный к загрузке в этом процессе: проверенный манифест,
 * локальный DEX в private storage и снимок конфига.
 *
 * <p>DEX никогда не исполняется из Downloads или другого общего каталога
 * (ТЗ §5.2 п.5): файл заранее копируется во внутреннее хранилище хоста.</p>
 */
public final class ResolvedModule {
    public final ModuleManifest manifest;
    public final File dexFile;
    public final String expectedDexSha256;
    public final byte[] configBytes;
    public final String configSha256;
    public final long generation;

    public ResolvedModule(ModuleManifest manifest, File dexFile, String expectedDexSha256,
                          byte[] configBytes, String configSha256, long generation) {
        this.manifest = manifest;
        this.dexFile = dexFile;
        this.expectedDexSha256 = expectedDexSha256;
        this.configBytes = configBytes != null ? configBytes : new byte[0];
        this.configSha256 = configSha256;
        this.generation = generation;
    }
}
