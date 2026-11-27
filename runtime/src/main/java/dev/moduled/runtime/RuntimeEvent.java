package dev.moduled.runtime;

/**
 * Категории runtime-журнала (ТЗ §8 RUN-09). В логах различаются: установлен,
 * выбран, скопирован в снимок, загружен, запущен, ошибка, заблокирован safe mode,
 * несовместим — а также служебные события bootstrap и снимков.
 */
public enum RuntimeEvent {
    BOOTSTRAP_START,
    BOOTSTRAP_DONE,
    BOOTSTRAP_SKIPPED,
    SNAPSHOT_REQUESTED,
    SNAPSHOT_IMPORTED,
    SNAPSHOT_STALE,
    SNAPSHOT_UNAVAILABLE,
    SNAPSHOT_REJECTED,
    SAFE_MODE_ACTIVE,
    SAFE_MODE_RESET,
    MODULE_INSTALLED,
    MODULE_SELECTED,
    MODULE_COPIED_TO_SNAPSHOT,
    MODULE_LOADED,
    MODULE_STARTED,
    MODULE_ERROR,
    MODULE_BLOCKED_SAFE_MODE,
    MODULE_INCOMPATIBLE,
    HOOK_INSTALLED,
    HOOK_FAILED,
    RESTART_REQUESTED,
    RESTART_REJECTED,
    CRASH_REPORTED,
    LOG_TRUNCATED
}
