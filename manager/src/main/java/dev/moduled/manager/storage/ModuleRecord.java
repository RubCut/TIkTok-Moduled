package dev.moduled.manager.storage;

import dev.moduled.core.ModuleManifest;
import java.io.File;

/** Установленный модуль: манифест, состояние, файлы и хеши. */
public final class ModuleRecord {
    public final ModuleManifest manifest;
    public final ModuleState state;
    public final File directory;
    public final String dexSha256;
    public final String configSha256;
    public final long configBytes;

    public ModuleRecord(ModuleManifest manifest, ModuleState state, File directory,
                        String dexSha256, String configSha256, long configBytes) {
        this.manifest = manifest;
        this.state = state;
        this.directory = directory;
        this.dexSha256 = dexSha256;
        this.configSha256 = configSha256;
        this.configBytes = configBytes;
    }

    public String id() {
        return manifest.id;
    }

    /** Изменения применятся только после публикации нового снимка и перезапуска. */
    public boolean pendingApply(long currentGeneration) {
        return state.appliedGeneration != currentGeneration;
    }
}
