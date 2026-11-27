package dev.moduled.runtime;

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
 * Сохранение crash-отчёта (ТЗ §18.2, §18.3).
 *
 * <p>Нельзя рассчитывать, что умирающий процесс успеет открыть Activity или
 * выполнить IPC: отчёт сохраняется локально ДО передачи исходному обработчику
 * исключений. Хранится до {@value dev.moduled.core.Limits#MAX_CRASH_REPORTS}
 * отчётов по 1 MiB; самый старый удаляется.</p>
 *
 * <p>Класс не использует Android API, поэтому проверяется JVM-тестами, включая
 * сценарии отказа записи и нехватки места.</p>
 */
public final class CrashReporter {

    private final File directory;
    private final RuntimeLog log;
    private final long maxReportBytes;
    private final int maxReports;

    public CrashReporter(File directory, RuntimeLog log) {
        this(directory, log, dev.moduled.core.Limits.MAX_CRASH_REPORT_BYTES,
                dev.moduled.core.Limits.MAX_CRASH_REPORTS);
    }

    public CrashReporter(File directory, RuntimeLog log, long maxReportBytes, int maxReports) {
        this.directory = directory;
        this.log = log;
        this.maxReportBytes = maxReportBytes;
        this.maxReports = maxReports;
    }

    /** Полное содержание отчёта: без обрезки стека, но с лимитом на размер файла. */
    public static String formatReport(String processName, String sessionId, long wallMs,
                                      Thread thread, Throwable throwable, boolean safeMode) {
        StringWriter sw = new StringWriter(1024);
        PrintWriter pw = new PrintWriter(sw);
        pw.println("TikTok Moduled runtime crash report");
        pw.println("process=" + processName);
        pw.println("session=" + sessionId);
        pw.println("wallMs=" + wallMs);
        pw.println("thread=" + (thread != null ? thread.getName() : "unknown"));
        pw.println("safeMode=" + safeMode);
        pw.println("runtimeVersion=" + RuntimeVersion.VERSION);
        pw.println("apiVersion=" + RuntimeVersion.API_VERSION);
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
     * Сохраняет отчёт и возвращает файл или null, если запись не удалась.
     * Отказ записи логируется, но не пробрасывается: умирающий процесс должен
     * передать управление исходному обработчику.
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
                return null;
            }
            File target = new File(directory, "crash-" + System.currentTimeMillis() + ".txt");
            Files.write(target.toPath(), data);
            if (truncated && log != null) {
                log.log(RuntimeEvent.LOG_TRUNCATED, null,
                        "Crash report truncated to " + maxReportBytes + " bytes; "
                                + "полный системный отчёт может быть доступен в logcat/tombstone");
            }
            prune();
            return target;
        } catch (IOException | RuntimeException e) {
            if (log != null) {
                log.log(RuntimeEvent.CRASH_REPORTED, null,
                        "Не удалось сохранить crash-отчёт: " + e);
            }
            return null;
        }
    }

    /** Оставляет не более maxReports последних отчётов. */
    private void prune() {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("crash-") && name.endsWith(".txt"));
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

    public List<File> reports() {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("crash-") && name.endsWith(".txt"));
        List<File> sorted = new ArrayList<>();
        if (files != null) {
            sorted.addAll(Arrays.asList(files));
            sorted.sort(Comparator.comparingLong(File::lastModified).reversed());
        }
        return sorted;
    }

    public String readLatest() {
        List<File> reports = reports();
        if (reports.isEmpty()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(reports.get(0).toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Устанавливает обработчик, сохраняющий отчёт и затем вызывающий предыдущий
     * handler. Проверка случая «хост сам заменил handler» выполняется в
     * {@link #verifyHandlerStillInstalled(Thread.UncaughtExceptionHandler)}.
     */
    public Thread.UncaughtExceptionHandler install(String processName, String sessionId,
                                                   boolean safeMode) {
        return installWithPrevious(processName, sessionId, safeMode,
                Thread.getDefaultUncaughtExceptionHandler());
    }

    /**
     * Вариант с явной передачей предыдущего обработчика: хост может заменить
     * handler после нас, и это проверяется отдельно (ТЗ §18.2).
     */
    public Thread.UncaughtExceptionHandler installWithPrevious(
            String processName, String sessionId, boolean safeMode,
            Thread.UncaughtExceptionHandler previous) {
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
            String report = formatReport(processName, sessionId, System.currentTimeMillis(),
                    thread, throwable, safeMode);
            save(report);
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
}
