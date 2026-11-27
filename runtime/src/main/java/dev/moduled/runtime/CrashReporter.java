package dev.moduled.runtime;

import dev.moduled.core.CrashReports;
import java.io.File;
import java.util.List;

/**
 * Crash-отчёты runtime (ТЗ §18.2). Логика хранения общая с менеджером и живёт в
 * {@link CrashReports}; здесь — привязка к журналу runtime и продукту.
 */
public final class CrashReporter {

    private static final String PRODUCT = "TikTok Moduled runtime";

    private final CrashReports reports;
    private final RuntimeLog log;

    public CrashReporter(File directory, RuntimeLog log) {
        this(directory, log, dev.moduled.core.Limits.MAX_CRASH_REPORT_BYTES,
                dev.moduled.core.Limits.MAX_CRASH_REPORTS);
    }

    public CrashReporter(File directory, RuntimeLog log, long maxReportBytes, int maxReports) {
        this.log = log;
        this.reports = new CrashReports(directory, new CrashReports.FailureListener() {
            @Override
            public void onReportFailure(String message) {
                log.log(RuntimeEvent.CRASH_REPORTED, null, message);
            }

            @Override
            public void onReportTruncated(String message) {
                log.log(RuntimeEvent.LOG_TRUNCATED, null, message);
            }
        }, maxReportBytes, maxReports);
    }

    public static String formatReport(String processName, String sessionId, long wallMs,
                                      Thread thread, Throwable throwable, boolean safeMode) {
        return CrashReports.formatReport(PRODUCT, processName, sessionId, wallMs, thread,
                throwable, safeMode,
                "runtime=" + RuntimeVersion.VERSION + " apiVersion=" + RuntimeVersion.API_VERSION);
    }

    public File save(String report) {
        return reports.save(report);
    }

    public List<File> reports() {
        return reports.reports();
    }

    public String readLatest() {
        return reports.readLatest();
    }

    public Thread.UncaughtExceptionHandler install(String processName, String sessionId,
                                                   boolean safeMode) {
        return reports.install(PRODUCT, processName, sessionId, safeMode,
                "runtime=" + RuntimeVersion.VERSION);
    }

    public Thread.UncaughtExceptionHandler installWithPrevious(
            String processName, String sessionId, boolean safeMode,
            Thread.UncaughtExceptionHandler previous) {
        return reports.installWithPrevious(PRODUCT, processName, sessionId, safeMode,
                "runtime=" + RuntimeVersion.VERSION, previous);
    }

    public static boolean verifyHandlerStillInstalled(Thread.UncaughtExceptionHandler expected) {
        return CrashReports.verifyHandlerStillInstalled(expected);
    }
}
