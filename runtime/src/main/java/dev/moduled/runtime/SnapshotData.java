package dev.moduled.runtime;

import dev.moduled.core.Snapshot;
import java.util.Collections;
import java.util.List;

/**
 * Результат запроса снимка (ТЗ §5.2, §20.2).
 *
 * <p>{@code stale == true} означает, что менеджер недоступен и используется
 * последний целый снимок: UI и логи обязаны показывать его устаревание.
 * Отключение модуля в недоступном менеджере не считается мгновенным отзывом уже
 * скопированного кода.</p>
 */
public final class SnapshotData {
    public final Snapshot snapshot;
    public final List<ResolvedModule> modules;
    public final boolean stale;
    public final long fetchedWallMs;
    public final String origin;
    public final boolean safeMode;

    public SnapshotData(Snapshot snapshot, List<ResolvedModule> modules, boolean stale,
                        long fetchedWallMs, String origin, boolean safeMode) {
        this.snapshot = snapshot;
        this.modules = Collections.unmodifiableList(modules);
        this.stale = stale;
        this.fetchedWallMs = fetchedWallMs;
        this.origin = origin;
        this.safeMode = safeMode;
    }

    public long ageMs(long nowWallMs) {
        return Math.max(0, nowWallMs - snapshot.createdWallMs);
    }
}
