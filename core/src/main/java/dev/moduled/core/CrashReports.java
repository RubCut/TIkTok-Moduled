package dev.moduled.core;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Запись crash-отчётов (ТЗ §18.2, §18.3, §18.4).
 *
 * <p>Чистый Java-код без Android: используется и встраиваемым runtime, и
 * TModules (у менеджера отдельный контур сбоев — §18.4). Отчёт сохраняется
 * локально ДО передачи исходному обработчику исключений, потому что умирающий
 * процесс может не успеть открыть Activity или выполнить IPC.</p>
 *
 * <p>Лимиты: до {@value Limits#MAX_CRASH_REPORTS} отчётов по
 * {@value Limits#MAX_CRASH_REPORT_BYTES} байт; превышение отображается как
 * усечение с указанием, где искать полный системный отчёт.</p>
 */
public final class CrashReports {

    private static final String PREFIX = "crash-";
    private static final String SUFFIX = ".txt";

    private final File directory;
    private final long maxReportBytes;
    private final int maxReports;
    private final FailureListener failureListener;

    /** Получатель сообщений об отказе записи (журнал процесса). */
    public interface FailureListener {
        void onReportFailure(String message);

        void onReportTruncated(String message);
    }

    public CrashReports(File directory, FailureListener failureListener) {
        this(directory, failureListener, Limits.MAX_CRASH_REPORT_BYTES, Limits.MAX_CRASH_REPORTS);
    }

    public CrashReports(File directory, FailureListener failureListener,
                        long maxReportBytes, int maxReports) {
        this.directory = directory;
        this.failureListener = failureListener;
        this.maxReportBytes = maxReportBytes;
        this.maxReports = maxReports;
    }

    /** Полный отчёт: контекст процесса, сессия, поток, safe mode и стек. */
    public static String formatReport(String product, String processName, String sessionId,
                                      long wallMs, Thread thread, Throwable throwable,
                                      boolean safeMode, String versionInfo) {
        StringWriter sw = new StringWriter(1024);
        PrintWriter pw = new PrintWriter(sw);
        pw.println(product + " crash report");
        pw.println("process=" + processName);
        pw.println("session=" + sessionId);
        pw.println("wallMs=" + wallMs);
        pw.println("thread=" + (thread != null ? thread.getName() : "unknown"));
        pw.println("safeMode=" + safeMode);
        if (versionInfo != null) {
            pw.println("versions=" + versionInfo);
        }
        pw.println("--- stack ---");
        if (throwable != null) {
            throwable.printStackTrace(pw);
        } else {
            pw.println("(no throwable)");
        }
        pw.flush();
        return sw.toString();
    }

    /**
     * Сохраняет отчёт. Возвращает файл или null при отказе записи: отказ
     * логируется, но не пробрасывается, чтобы не мешать исходному обработчику.
     */
    public File save(String report) {
        if (report == null) {
            return null;
        }
        byte[] data = report.getBytes(StandardCharsets.UTF_8);
        boolean truncated = false;
        if (data.length > maxReportBytes) {
            data = Arrays.copyOf(data, (int) maxReportBytes);
            truncated = true;
        }
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                notifyFailure("Каталог отчётов недоступен: " + directory);
                return null;
            }
            File target = new File(directory, PREFIX + System.currentTimeMillis() + SUFFIX);
            Files.write(target.toPath(), data);
            if (truncated) {
                notifyTruncated("Отчёт усечён до " + maxReportBytes + " байт; полный системный "
                        + "отчёт может быть доступен в logcat или tombstone");
            }
            prune();
            return target;
        } catch (IOException | RuntimeException e) {
            notifyFailure("Не удалось сохранить crash-отчёт: " + e);
            return null;
        }
    }

    private void prune() {
        File[] files = listFiles();
        if (files == null || files.length <= maxReports) {
            return;
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < sorted.size() - maxReports; i++) {
            //noinspection ResultOfMethodCallIgnored
            sorted.get(i).delete();
        }
    }

    private File[] listFiles() {
        return directory.listFiles((dir, name) -> name.startsWith(PREFIX) && name.endsWith(SUFFIX));
    }

    /** Отчёты от нового к старому. */
    public List<File> reports() {
        File[] files = listFiles();
        List<File> sorted = new ArrayList<>();
        if (files != null) {
            sorted.addAll(Arrays.asList(files));
            sorted.sort(Comparator.comparingLong(File::lastModified).reversed());
        }
        return sorted;
    }

    public String read(File report) {
        try {
            return new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public String readLatest() {
        List<File> reports = reports();
        return reports.isEmpty() ? null : read(reports.get(0));
    }

    /**
     * Устанавливает обработчик, сохраняющий отчёт и вызывающий предыдущий.
     * Обработчик намеренно не тянет UI: ошибка сохраняется до любых экранов.
     */
    public Thread.UncaughtExceptionHandler install(String product, String processName,
                                                   String sessionId, boolean safeMode,
                                                   String versionInfo) {
        return installWithPrevious(product, processName, sessionId, safeMode, versionInfo,
                Thread.getDefaultUncaughtExceptionHandler());
    }

    public Thread.UncaughtExceptionHandler installWithPrevious(
            String product, String processName, String sessionId, boolean safeMode,
            String versionInfo, Thread.UncaughtExceptionHandler previous) {
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
            save(formatReport(product, processName, sessionId, System.currentTimeMillis(),
                    thread, throwable, safeMode, versionInfo));
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(handler);
        return handler;
    }

    public static boolean verifyHandlerStillInstalled(Thread.UncaughtExceptionHandler expected) {
        return Thread.getDefaultUncaughtExceptionHandler() == expected;
    }

    private void notifyFailure(String message) {
        if (failureListener != null) {
            failureListener.onReportFailure(message);
        }
    }

    private void notifyTruncated(String message) {
        if (failureListener != null) {
            failureListener.onReportTruncated(message);
        }
    }
}
