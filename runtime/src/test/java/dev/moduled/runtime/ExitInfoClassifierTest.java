package dev.moduled.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import dev.moduled.core.CrashPolicy;
import org.junit.Test;

/** CR-05/CR-06: классификация причин завершения процесса. */
public class ExitInfoClassifierTest {

    @Test
    public void crashesAreCounted() {
        assertEquals(CrashPolicy.EventType.CRASH,
                ExitInfoClassifier.classify(ExitInfoClassifier.REASON_CRASH));
        assertEquals(CrashPolicy.EventType.CRASH,
                ExitInfoClassifier.classify(ExitInfoClassifier.REASON_CRASH_NATIVE));
        assertTrue(ExitInfoClassifier.affectsCrashCounter(ExitInfoClassifier.REASON_CRASH));
    }

    /** CR-05: force stop, штатный выход и low memory не являются крашами. */
    @Test
    public void nonCrashReasonsAreNotCounted() {
        assertEquals(CrashPolicy.EventType.FORCE_STOP,
                ExitInfoClassifier.classify(ExitInfoClassifier.REASON_USER_REQUESTED));
        assertEquals(CrashPolicy.EventType.FORCE_STOP,
                ExitInfoClassifier.classify(ExitInfoClassifier.REASON_USER_STOPPED));
        assertEquals(CrashPolicy.EventType.LOW_MEMORY,
                ExitInfoClassifier.classify(ExitInfoClassifier.REASON_LOW_MEMORY));
        assertEquals(CrashPolicy.EventType.NORMAL_EXIT,
                ExitInfoClassifier.classify(ExitInfoClassifier.REASON_EXIT_SELF));
        assertFalse(ExitInfoClassifier.affectsCrashCounter(ExitInfoClassifier.REASON_USER_REQUESTED));
        assertFalse(ExitInfoClassifier.affectsCrashCounter(ExitInfoClassifier.REASON_LOW_MEMORY));
        assertFalse(ExitInfoClassifier.affectsCrashCounter(ExitInfoClassifier.REASON_EXIT_SELF));
    }

    /** CR-05: ANR — отдельный тип, не добавляется в счётчик крашей. */
    @Test
    public void anrIsSeparate() {
        assertEquals(CrashPolicy.EventType.ANR,
                ExitInfoClassifier.classify(ExitInfoClassifier.REASON_ANR));
        assertTrue(ExitInfoClassifier.affectsCrashCounter(ExitInfoClassifier.REASON_ANR));
        assertNotEquals(CrashPolicy.EventType.CRASH,
                ExitInfoClassifier.classify(ExitInfoClassifier.REASON_ANR));
    }

    @Test
    public void unknownReasonsStayUnknown() {
        assertEquals(CrashPolicy.EventType.UNKNOWN,
                ExitInfoClassifier.classify(ExitInfoClassifier.REASON_UNKNOWN));
        assertEquals(CrashPolicy.EventType.UNKNOWN,
                ExitInfoClassifier.classify(ExitInfoClassifier.REASON_OTHER));
        assertFalse(ExitInfoClassifier.affectsCrashCounter(ExitInfoClassifier.REASON_UNKNOWN));
    }

    /** CR-06: ключ события исключает повторный учёт одного exit event. */
    @Test
    public void eventKeyIsStableAndDistinct() {
        String first = ExitInfoClassifier.eventKey(1000, 4, 42, "com.host");
        String same = ExitInfoClassifier.eventKey(1000, 4, 42, "com.host");
        String otherPid = ExitInfoClassifier.eventKey(1000, 4, 43, "com.host");
        String otherReason = ExitInfoClassifier.eventKey(1000, 6, 42, "com.host");
        assertEquals(first, same);
        assertFalse(first.equals(otherPid));
        assertFalse(first.equals(otherReason));
    }

    /** CR-06: покрытие детекции честно описывается для каждой версии ОС. */
    @Test
    public void coverageDescriptionIsHonest() {
        String modern = ExitInfoClassifier.coverageDescription(34);
        assertTrue(modern.contains("ApplicationExitInfo"));
        String legacy = ExitInfoClassifier.coverageDescription(28);
        assertTrue(legacy.contains("Android 9–10"));
        assertTrue(legacy.contains("uncaught"));
        assertFalse("Legacy coverage must not claim full support",
                legacy.contains("ApplicationExitInfo: учитываются"));
    }
}
