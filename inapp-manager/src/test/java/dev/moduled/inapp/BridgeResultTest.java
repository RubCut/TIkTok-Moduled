package dev.moduled.inapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ТЗ §17.3: недоступный мост — явная причина, а не имитация состояния. */
public class BridgeResultTest {

    @Test
    public void unavailableCarriesReason() {
        BridgeClient.BridgeResult result = BridgeClient.BridgeResult.unavailable("нет provider");
        assertFalse(result.available);
        assertFalse(result.ok);
        assertEquals("BRIDGE_UNAVAILABLE", result.errorCode);
        assertTrue(result.message.contains("provider"));
    }

    @Test
    public void nullBundleIsExplicitError() {
        BridgeClient.BridgeResult result = BridgeClient.BridgeResult.of(null);
        assertTrue(result.available);
        assertFalse(result.ok);
        assertEquals("BRIDGE_NO_RESPONSE", result.errorCode);
    }

    // Разбор успешного Bundle проверяется интеграционно на устройстве: android.jar
    // в JVM-тестах содержит заглушки, а имитация Bundle была бы нечестной проверкой.
}
