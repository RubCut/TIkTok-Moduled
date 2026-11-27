package dev.moduled.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ТЗ §19: перезапуск best-effort, честные статусы вместо ложного успеха. */
public class RestartResultTest {

    @Test
    public void scheduledIsTheOnlySuccessStatus() {
        RestartResult scheduled = RestartResult.of(RestartResult.Status.SCHEDULED, "restart planned");
        assertTrue(scheduled.isScheduled());
        assertEquals("restart planned", scheduled.message());
    }

    @Test
    public void nonScheduledStatusesAreNotSuccess() {
        for (RestartResult.Status status : new RestartResult.Status[] {
                RestartResult.Status.RATE_LIMITED,
                RestartResult.Status.UNAVAILABLE,
                RestartResult.Status.MANUAL_REQUIRED}) {
            RestartResult result = RestartResult.of(status, status.name());
            assertFalse(status.name(), result.isScheduled());
            assertEquals(status, result.status());
            assertTrue(result.toString().contains(status.name()));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void statusIsRequired() {
        RestartResult.of(null, "no status");
    }
}
