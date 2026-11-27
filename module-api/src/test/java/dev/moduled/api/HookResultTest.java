package dev.moduled.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HookResultTest {

    private static final HookHandle HANDLE = new HookHandle() {
        @Override
        public String targetDescription() {
            return "com.example.Target#method()";
        }

        @Override
        public boolean unhook() {
            return true;
        }
    };

    @Test
    public void successCarriesHandleAndNoErrorCode() {
        HookResult result = HookResult.hooked(HANDLE);
        assertTrue(result.isSuccess());
        assertNull(result.errorCode());
        assertSame(HANDLE, result.handle());
        assertTrue(result.toString().contains("com.example.Target#method()"));
    }

    @Test
    public void failureCarriesExplicitErrorCode() {
        HookResult result = HookResult.failed(HookResult.ERR_BACKEND_UNAVAILABLE,
                "No hook backend is available in this process");
        assertFalse(result.isSuccess());
        assertEquals(HookResult.ERR_BACKEND_UNAVAILABLE, result.errorCode());
        assertTrue(result.message().contains("backend"));
        assertTrue(result.toString().contains("HOOK_BACKEND_UNAVAILABLE"));
    }

    /** Отсутствие результата не должно выглядеть как успех. */
    @Test(expected = IllegalStateException.class)
    public void failureHasNoHandle() {
        HookResult.failed(HookResult.ERR_TARGET_NOT_FOUND, "not found").handle();
    }

    @Test(expected = IllegalArgumentException.class)
    public void successRequiresHandle() {
        HookResult.hooked(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void failureRequiresErrorCode() {
        HookResult.failed("", "message");
    }
}
