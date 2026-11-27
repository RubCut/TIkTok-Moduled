package dev.moduled.core;

/**
 * Проектные верхние границы первой версии (ТЗ §10.3 PKG-03).
 * Значения зафиксированы в docs/DECISIONS.md (ADR-001) и покрыты тестами.
 */
public final class Limits {
    private Limits() {
    }

    /** Descriptor / внутренний manifest: 256 KiB. */
    public static final int MAX_DESCRIPTOR_BYTES = 256 * 1024;

    /** ZIP-модуль: 64 MiB (размер файла архива). */
    public static final long MAX_ARCHIVE_FILE_BYTES = 64L * 1024 * 1024;

    /** Один DEX: 64 MiB распакованных байт. */
    public static final long MAX_DEX_BYTES = 64L * 1024 * 1024;

    /** Суммарно распакованных байт архива: защита от zip-bomb (ADR-001). */
    public static final long MAX_TOTAL_UNPACKED_BYTES = 128L * 1024 * 1024;

    /** Текстовый конфиг модуля: 1 MiB точных байт (ТЗ §12 CFG-01). */
    public static final int MAX_CONFIG_BYTES = 1024 * 1024;

    /** ID модуля: 128 ASCII-символов (ТЗ §10.1). */
    public static final int MAX_MODULE_ID_LENGTH = 128;

    /** Runtime-журнал на процесс: 5 MiB с ротацией (ТЗ §22). */
    public static final long MAX_RUNTIME_LOG_BYTES = 5L * 1024 * 1024;

    /** Crash-отчёты: до 5 штук по 1 MiB (ТЗ §22). */
    public static final int MAX_CRASH_REPORTS = 5;
    public static final long MAX_CRASH_REPORT_BYTES = 1024L * 1024;

    /** Каталог репозитория: 2 MiB (ADR-001). */
    public static final long MAX_CATALOG_BYTES = 2L * 1024 * 1024;

    /** Скачиваемый архив модуля из репозитория: 64 MiB. */
    public static final long MAX_DOWNLOAD_BYTES = MAX_ARCHIVE_FILE_BYTES;
}
