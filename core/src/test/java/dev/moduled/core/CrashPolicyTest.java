package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Аварийная защита (ТЗ §18.1 CR-01..CR-08). Время подаётся тестовыми часами,
 * состояние — тестовым хранилищем, поэтому политика проверяется без Android.
 */
public class CrashPolicyTest {

    private static final class FakeClock implements CrashPolicy.Clock {
        long monotonic;
        long wall = 1_700_000_000_000L;

        @Override
        public long monotonicMs() {
            return monotonic;
        }

        @Override
        public long wallMs() {
            return wall;
        }

        void advanceMs(long ms) {
            monotonic += ms;
            wall += ms;
        }
    }

    private static final class MemoryStore implements CrashPolicy.StateStore {
        final Map<String, String> data = new HashMap<>();
        String corruptValue;
        boolean failOnLoad;

        @Override
        public String load() {
            if (failOnLoad) {
                throw new IllegalStateException("storage unavailable");
            }
            if (corruptValue != null) {
                return corruptValue;
            }
            return data.get("crash-state");
        }

        @Override
        public void save(String state) {
            data.put("crash-state", state);
        }

        @Override
        public void clear() {
            data.clear();
        }
    }

    private final FakeClock clock = new FakeClock();
    private final MemoryStore store = new MemoryStore();

    private CrashPolicy policy() {
        return new CrashPolicy(clock, store);
    }

    private CrashPolicy.Event crash(String id) {
        return new CrashPolicy.Event(id, CrashPolicy.EventType.CRASH, clock.monotonicMs(), clock.wallMs());
    }

    private CrashPolicy.Event event(String id, CrashPolicy.EventType type) {
        return new CrashPolicy.Event(id, type, clock.monotonicMs(), clock.wallMs());
    }

    /** CR-01: порог — три подтверждённых сбоя в 10-минутном окне (не «два и больше»). */
    @Test
    public void twoCrashesDoNotLatchButThreeDo() {
        CrashPolicy policy = policy();
        assertFalse(policy.shouldRunSafeMode());

        assertTrue(policy.recordEvent(crash("c1")));
        clock.advanceMs(1000);
        assertTrue(policy.recordEvent(crash("c2")));
        assertEquals(2, policy.recentCrashCount());
        assertFalse("Two crashes must not trigger safe mode", policy.shouldRunSafeMode());

        clock.advanceMs(1000);
        assertTrue(policy.recordEvent(crash("c3")));
        assertEquals(3, policy.recentCrashCount());
        assertTrue("Third crash in window must latch safe mode", policy.shouldRunSafeMode());
    }

    @Test
    public void windowIsTenMinutesSliding() {
        assertEquals(10L * 60 * 1000, CrashPolicy.WINDOW_MS);
        assertEquals(3, CrashPolicy.CRASH_THRESHOLD);

        CrashPolicy policy = policy();
        policy.recordEvent(crash("c1"));
        clock.advanceMs(60_000);
        policy.recordEvent(crash("c2"));
        // Первый сбой выпадает из окна через 10 минут.
        clock.advanceMs(10L * 60 * 1000);
        policy.recordEvent(crash("c3"));
        assertEquals(2, policy.recentCrashCount());
        assertFalse(policy.shouldRunSafeMode());

        clock.advanceMs(1000);
        policy.recordEvent(crash("c4"));
        assertEquals(3, policy.recentCrashCount());
        assertTrue(policy.shouldRunSafeMode());
    }

    /** CR-02: защёлка переживает перезапуск и не снимается обычным стартом. */
    @Test
    public void latchSurvivesProcessRestart() {
        CrashPolicy first = policy();
        first.recordEvent(crash("c1"));
        first.recordEvent(crash("c2"));
        first.recordEvent(crash("c3"));
        assertTrue(first.shouldRunSafeMode());
        assertFalse("State must be persisted", store.data.isEmpty());

        CrashPolicy afterRestart = new CrashPolicy(clock, store);
        assertTrue("Safe mode must survive restart", afterRestart.shouldRunSafeMode());
    }

    /** CR-05: force stop, штатный выход и low memory не считаются крашами. */
    @Test
    public void nonCrashExitsAreNotCounted() {
        CrashPolicy policy = policy();
        assertFalse(policy.recordEvent(event("f1", CrashPolicy.EventType.FORCE_STOP)));
        assertFalse(policy.recordEvent(event("n1", CrashPolicy.EventType.NORMAL_EXIT)));
        assertFalse(policy.recordEvent(event("l1", CrashPolicy.EventType.LOW_MEMORY)));
        assertFalse(policy.recordEvent(event("u1", CrashPolicy.EventType.UNKNOWN)));
        assertEquals(0, policy.recentCrashCount());
        assertFalse(policy.shouldRunSafeMode());

        // Даже вперемешку с реальными крашами порог не достигается.
        policy.recordEvent(crash("c1"));
        policy.recordEvent(event("f2", CrashPolicy.EventType.FORCE_STOP));
        policy.recordEvent(crash("c2"));
        policy.recordEvent(event("n2", CrashPolicy.EventType.NORMAL_EXIT));
        assertEquals(2, policy.recentCrashCount());
        assertFalse(policy.shouldRunSafeMode());
    }

    /** CR-05: ANR — отдельный тип события с отдельным счётчиком. */
    @Test
    public void anrHasSeparateCounter() {
        CrashPolicy policy = policy();
        policy.recordEvent(event("a1", CrashPolicy.EventType.ANR));
        policy.recordEvent(event("a2", CrashPolicy.EventType.ANR));
        policy.recordEvent(event("a3", CrashPolicy.EventType.ANR));
        assertEquals(3, policy.recentAnrCount());
        assertEquals(0, policy.recentCrashCount());
        assertFalse("ANR alone must not latch safe mode", policy.shouldRunSafeMode());

        policy.recordEvent(crash("c1"));
        assertEquals(1, policy.recentCrashCount());
        assertEquals(3, policy.recentAnrCount());
    }

    /** CR-06: один и тот же exit event не учитывается повторно. */
    @Test
    public void duplicateEventIdIsCountedOnce() {
        CrashPolicy policy = policy();
        assertTrue(policy.recordEvent(crash("same-id")));
        assertFalse(policy.recordEvent(crash("same-id")));
        assertFalse(policy.recordEvent(crash("same-id")));
        assertEquals(1, policy.recentCrashCount());
        assertFalse(policy.shouldRunSafeMode());

        // Дедупликация сохраняется между запусками.
        CrashPolicy afterRestart = new CrashPolicy(clock, store);
        assertFalse(afterRestart.recordEvent(crash("same-id")));
        assertEquals(1, afterRestart.recentCrashCount());
    }

    /** CR-07: сброс — явное действие; после него модули снова разрешены. */
    @Test
    public void explicitResetClearsLatch() {
        CrashPolicy policy = policy();
        policy.recordEvent(crash("c1"));
        policy.recordEvent(crash("c2"));
        policy.recordEvent(crash("c3"));
        assertTrue(policy.shouldRunSafeMode());

        policy.reset();
        assertFalse(policy.shouldRunSafeMode());
        assertEquals(0, policy.recentCrashCount());

        CrashPolicy afterRestart = new CrashPolicy(clock, store);
        assertFalse("Reset must be persisted", afterRestart.shouldRunSafeMode());

        // После сброса прежние id не «воскресают»: новый сбой считается заново.
        assertTrue(afterRestart.recordEvent(crash("c1")));
        assertEquals(1, afterRestart.recentCrashCount());
    }

    /** CR-08: повреждённое состояние → консервативный safe mode. */
    @Test
    public void corruptedStateFailsSafe() {
        store.corruptValue = "\u0000\u0001garbage not a state file";
        CrashPolicy policy = policy();
        assertTrue(policy.isCorrupted());
        assertTrue("Corrupted state must force safe mode", policy.shouldRunSafeMode());
    }

    @Test
    public void unknownStateVersionFailsSafe() {
        store.corruptValue = "v999\nsafeMode=0\n";
        CrashPolicy policy = policy();
        assertTrue(policy.isCorrupted());
        assertTrue(policy.shouldRunSafeMode());
    }

    /** CR-08: недоступное хранилище не роняет запуск и не снимает защиту молча. */
    @Test
    public void unavailableStorageDoesNotCrashPolicy() {
        store.failOnLoad = true;
        CrashPolicy policy = policy();
        assertFalse(policy.isCorrupted());
        assertTrue(policy.recordEvent(crash("c1")));
        assertEquals(1, policy.recentCrashCount());
    }

    /** Персистентность: счётчики восстанавливаются после перезапуска в пределах окна. */
    @Test
    public void countersAreRestoredWithinWindow() {
        CrashPolicy first = policy();
        first.recordEvent(crash("c1"));
        clock.advanceMs(30_000);
        first.recordEvent(crash("c2"));

        CrashPolicy second = new CrashPolicy(clock, store);
        assertEquals(2, second.recentCrashCount());
        assertFalse(second.shouldRunSafeMode());
        second.recordEvent(crash("c3"));
        assertTrue(second.shouldRunSafeMode());
    }

    @Test
    public void eventsOutsideWindowArePrunedAfterRestart() {
        CrashPolicy first = policy();
        first.recordEvent(crash("c1"));
        first.recordEvent(crash("c2"));
        clock.advanceMs(CrashPolicy.WINDOW_MS + 1);

        CrashPolicy second = new CrashPolicy(clock, store);
        assertEquals(0, second.recentCrashCount());
        second.recordEvent(crash("c3"));
        assertFalse(second.shouldRunSafeMode());
    }
}
