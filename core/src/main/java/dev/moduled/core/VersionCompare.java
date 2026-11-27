package dev.moduled.core;

/**
 * Сравнение версий модулей по целому {@code versionCode} (ТЗ §13.3),
 * а не лексикографически по {@code version}.
 */
public final class VersionCompare {
    private VersionCompare() {
    }

    /** @return отрицательное, если a старше b; 0 — равны; положительное — a новее. */
    public static int compareVersionCodes(long a, long b) {
        return Long.compare(a, b);
    }

    public static boolean isUpdate(long installedCode, long availableCode) {
        return availableCode > installedCode;
    }

    public static boolean isDowngrade(long installedCode, long availableCode) {
        return availableCode < installedCode;
    }
}
