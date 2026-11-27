package dev.moduled.runtime;

import dev.moduled.core.Result;

/**
 * Источник согласованного снимка (ТЗ §5.2 п.3, §20.2).
 * Реализации: мост менеджера и локальный кеш последнего целого снимка.
 */
public interface SnapshotSource {
    Result<SnapshotData> load(boolean safeMode);
}
