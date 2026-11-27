package dev.moduled.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** §19: перезапуск не должен создавать бесконечный цикл. */
public class RestartRateLimiterTest {

    @Test
    public void allowsUpToLimitInsideWindow() {
        RestartRateLimiter limiter = new RestartRateLimiter(2, 1000);
        assertTrue(limiter.allow(0));
        assertTrue(limiter.allow(100));
        assertFalse(limiter.allow(200));
        assertEquals(2, limiter.recentRequests(200));
    }

    @Test
    public void windowSlides() {
        RestartRateLimiter limiter = new RestartRateLimiter(2, 1000);
        limiter.allow(0);
        limiter.allow(100);
        assertFalse(limiter.allow(500));
        assertTrue("Requests must expire with the window", limiter.allow(1100));
    }

    @Test
    public void resetClearsHistory() {
        RestartRateLimiter limiter = new RestartRateLimiter(1, 10_000);
        assertTrue(limiter.allow(0));
        assertFalse(limiter.allow(1));
        limiter.reset();
        assertTrue(limiter.allow(2));
    }

    @Test
    public void defaultPolicyIsTwoPerFiveMinutes() {
        RestartRateLimiter limiter = new RestartRateLimiter();
        assertTrue(limiter.allow(0));
        assertTrue(limiter.allow(1));
        assertFalse(limiter.allow(2));
        assertTrue(limiter.allow(5L * 60 * 1000 + 1));
    }
}
