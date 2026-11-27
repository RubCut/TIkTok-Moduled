package dev.moduled.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Согласованный снимок модулей (ТЗ §5.2, §20.2): поколение, список модулей,
 * версии, хеши и связанный config snapshot. Читатель видит целиком старое
 * или целиком новое поколение — публикация атомарна на стороне хранилища.
 */
public final class Snapshot {
    public final long generation;
    public final long createdWallMs;
    public final List<ModuleEntry> modules;

    public Snapshot(long generation, long createdWallMs, List<ModuleEntry> modules) {
        this.generation = generation;
        this.createdWallMs = createdWallMs;
        this.modules = Collections.unmodifiableList(new ArrayList<>(modules));
    }

    public static final class ModuleEntry {
        public final String id;
        public final long versionCode;
        public final String dexSha256;
        public final String configSha256;
        public final boolean enabled;

        public ModuleEntry(String id, long versionCode, String dexSha256,
                           String configSha256, boolean enabled) {
            this.id = id;
            this.versionCode = versionCode;
            this.dexSha256 = dexSha256;
            this.configSha256 = configSha256;
            this.enabled = enabled;
        }
    }

    /** Активные модули в детерминированном порядке (RUN-06: стабильная сортировка по ID). */
    public List<ModuleEntry> activeSortedById() {
        List<ModuleEntry> active = new ArrayList<>();
        for (ModuleEntry entry : modules) {
            if (entry.enabled) {
                active.add(entry);
            }
        }
        active.sort((a, b) -> a.id.compareTo(b.id));
        return active;
    }
}
