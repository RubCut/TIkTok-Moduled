package dev.moduled.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** §18.2: отчёт сохраняется локально до передачи исходному обработчику. */
public class CrashReporterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CrashReporter reporter(File dir) {
        RuntimeLog log = new RuntimeLog(new File(dir, "runtime.log"), "test");
        return new CrashReporter(new File(dir, "crash"), log, 1024, 3);
    }

    @Test
    public void reportContainsContextWithoutGuessingCulprit() {
        String report = CrashReporter.formatReport("com.host", "session-1",
                1_700_000_000_000L, new Thread("main"),
                new IllegalStateException("boom"), false);
        assertTrue(report.contains("process=com.host"));
        assertTrue(report.contains("session=session-1"));
        assertTrue(report.contains("thread=main"));
        assertTrue(report.contains("safeMode=false"));
        assertTrue(report.contains("IllegalStateException: boom"));
        assertTrue(report.contains("--- stack ---"));
        // CR-04: отчёт не назначает виновный модуль без данных.
        assertTrue(!report.contains("culprit"));
        assertTrue(!report.contains("винов"));
    }

    @Test
    public void savesAndReadsBackLatestReport() throws IOException {
        File dir = tmp.newFolder("store");
        CrashReporter reporter = reporter(dir);
        File saved = reporter.save(CrashReporter.formatReport("p", "s", 1L, null,
                new RuntimeException("x"), true));
        assertNotNull(saved);
        assertTrue(saved.isFile());
        String latest = reporter.readLatest();
        assertNotNull(latest);
        assertTrue(latest.contains("safeMode=true"));
        assertEquals(1, reporter.reports().size());
    }

    @Test
    public void truncatesOversizedReport() throws IOException {
        File dir = tmp.newFolder("big");
        CrashReporter reporter = reporter(dir);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            huge.append("stack line\n");
        }
        File saved = reporter.save(huge.toString());
        assertNotNull(saved);
        assertTrue("Report must respect the 1 KiB test budget", saved.length() <= 1024);
    }

    /** Лимит числа отчётов: старый удаляется, новые остаются. */
    @Test
    public void keepsAtMostMaxReports() throws IOException {
        File dir = tmp.newFolder("many");
        CrashReporter reporter = reporter(dir);
        for (int i = 0; i < 6; i++) {
            reporter.save("report " + i);
            // lastModified имеет секундную гранулярность — помогаем порядку.
            Thread.yield();
        }
        assertTrue("Expected <= 3 reports, got " + reporter.reports().size(),
                reporter.reports().size() <= 3);
    }

    @Test
    public void writeFailureDoesNotThrow() throws IOException {
        File blocked = tmp.newFolder("blocked");
        File crashDir = new File(blocked, "crash");
        // Делаем каталог недоступным для записи файлом с тем же именем.
        assertTrue(crashDir.getParentFile().isDirectory());
        assertTrue(crashDir.createNewFile());
        RuntimeLog log = new RuntimeLog(new File(blocked, "runtime.log"), "test");
        CrashReporter reporter = new CrashReporter(crashDir, log, 1024, 3);
        assertNull("Save must fail gracefully", reporter.save("report"));
        assertNull(reporter.readLatest());
        assertTrue(reporter.reports().isEmpty());
    }

    @Test
    public void handlerDelegatesToPreviousHandler() throws IOException {
        File dir = tmp.newFolder("handler");
        CrashReporter reporter = reporter(dir);
        final Throwable[] captured = new Throwable[1];
        Thread.UncaughtExceptionHandler previous = (thread, throwable) -> captured[0] = throwable;
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Thread.UncaughtExceptionHandler installed =
                    reporter.installWithPrevious("p", "s", false, previous);
            assertTrue(CrashReporter.verifyHandlerStillInstalled(installed));
            RuntimeException failure = new RuntimeException("host died");
            installed.uncaughtException(new Thread("t"), failure);
            assertEquals(failure, captured[0]);
            assertNotNull("Report must be saved before delegating", reporter.readLatest());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }

    @Test
    public void savedReportIsUtf8() throws IOException {
        File dir = tmp.newFolder("utf8");
        CrashReporter reporter = reporter(dir);
        File saved = reporter.save("отчёт на русском");
        assertNotNull(saved);
        String text = new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("отчёт на русском"));
    }
}
