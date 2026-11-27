package dev.moduled.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** §22: структура события, ротация по бюджету 5 MiB, отсутствие бесконечного роста. */
public class RuntimeLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void entryHasAllMandatoryFields() throws IOException {
        File file = new File(tmp.newFolder("logs"), "runtime.log");
        RuntimeLog log = new RuntimeLog(file, "com.host.main");
        log.log(RuntimeEvent.MODULE_STARTED, "region.module", "модуль запущен");

        List<String> entries = log.readEntries();
        assertEquals(1, entries.size());
        String[] parts = entries.get(0).split("\\|");
        assertEquals("wall|level|process|session|event|module|message", 7, parts.length);
        assertEquals("I", parts[1]);
        assertEquals("com.host.main", parts[2]);
        assertEquals(log.sessionId(), parts[3]);
        assertEquals("MODULE_STARTED", parts[4]);
        assertEquals("region.module", parts[5]);
        assertEquals("модуль запущен", parts[6]);
    }

    /** RUN-09: состояния различимы в журнале. */
    @Test
    public void distinguishesModuleStates() throws IOException {
        File file = new File(tmp.newFolder("states"), "runtime.log");
        RuntimeLog log = new RuntimeLog(file, "p");
        log.log(RuntimeEvent.MODULE_INSTALLED, "a.module", "установлен");
        log.log(RuntimeEvent.MODULE_SELECTED, "a.module", "выбран");
        log.log(RuntimeEvent.MODULE_COPIED_TO_SNAPSHOT, "a.module", "скопирован в снимок");
        log.log(RuntimeEvent.MODULE_LOADED, "a.module", "загружен");
        log.log(RuntimeEvent.MODULE_STARTED, "a.module", "запущен");
        log.log(RuntimeEvent.MODULE_ERROR, "a.module", "ошибка");
        log.log(RuntimeEvent.MODULE_BLOCKED_SAFE_MODE, "a.module", "safe mode");
        log.log(RuntimeEvent.MODULE_INCOMPATIBLE, "a.module", "несовместим");

        List<String> entries = log.readEntries();
        assertEquals(8, entries.size());
        for (String expected : new String[] {"MODULE_INSTALLED", "MODULE_SELECTED",
                "MODULE_COPIED_TO_SNAPSHOT", "MODULE_LOADED", "MODULE_STARTED", "MODULE_ERROR",
                "MODULE_BLOCKED_SAFE_MODE", "MODULE_INCOMPATIBLE"}) {
            assertTrue("Missing " + expected, entries.stream().anyMatch(e -> e.contains(expected)));
        }
        assertEquals('E', log.readEntries().get(5).charAt(log.readEntries().get(5).indexOf('|') + 1));
    }

    @Test
    public void rotatesWhenBudgetExceeded() throws IOException {
        File file = new File(tmp.newFolder("rotate"), "runtime.log");
        RuntimeLog log = new RuntimeLog(file, "p", 2048);
        for (int i = 0; i < 400; i++) {
            log.log(RuntimeEvent.MODULE_LOADED, "m.module",
                    "сообщение номер " + i + " с достаточной длиной для ротации журнала");
        }
        assertTrue("Log must stay within budget, size=" + log.sizeBytes(),
                log.sizeBytes() <= 2048 + 256);
        List<String> entries = log.readEntries();
        assertTrue(entries.stream().anyMatch(e -> e.contains("LOG_TRUNCATED")));
        assertFalse("Log must not be empty after rotation", entries.isEmpty());
    }

    @Test
    public void messageSeparatorsAreEscaped() throws IOException {
        File file = new File(tmp.newFolder("escape"), "runtime.log");
        RuntimeLog log = new RuntimeLog(file, "p|ipe");
        log.log(RuntimeEvent.MODULE_ERROR, "m", "первая|вторая\nтретья");
        String entry = log.readEntries().get(0);
        assertEquals("Pipes and newlines must not break the record",
                7, entry.split("\\|").length);
        assertTrue(entry.contains("первая/вторая третья"));
    }

    @Test
    public void throwableIsAppended() throws IOException {
        File file = new File(tmp.newFolder("throwable"), "runtime.log");
        RuntimeLog log = new RuntimeLog(file, "p");
        log.log(RuntimeEvent.MODULE_ERROR, "m", "сбой", new IllegalStateException("bad state"));
        String entry = log.readEntries().get(0);
        assertTrue(entry.contains("IllegalStateException: bad state"));
    }

    @Test
    public void missingLogFileIsNotAnError() throws IOException {
        File file = new File(tmp.newFolder("empty"), "absent.log");
        RuntimeLog log = new RuntimeLog(file, "p");
        assertTrue(log.readEntries().isEmpty());
        assertEquals(0, log.sizeBytes());
    }

    @Test
    public void logIsWrittenAsUtf8() throws IOException {
        File file = new File(tmp.newFolder("utf8"), "runtime.log");
        RuntimeLog log = new RuntimeLog(file, "p");
        log.log(RuntimeEvent.MODULE_STARTED, "регион.модуль", "кириллица и emoji 😀");
        String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(raw.contains("😀"));
    }
}
