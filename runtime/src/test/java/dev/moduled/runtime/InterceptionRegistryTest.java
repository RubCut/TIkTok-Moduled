package dev.moduled.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.moduled.api.HookCallback;
import dev.moduled.api.HookParam;
import dev.moduled.api.HookHandle;
import dev.moduled.api.HookResult;
import dev.moduled.runtime.fixture.FakeModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Семантика перехвата (ТЗ §9.1): аргументы, замена результата, исключение,
 * порядок нескольких хуков, снятие хука. Проверяется на кооперативном реестре
 * тестового хоста — это не ART-хук, и в документации это зафиксировано.
 */
public class InterceptionRegistryTest {

    private static String key() throws Exception {
        Method method = FakeModule.InterceptedTarget.class.getMethod("greeting", String.class);
        return InterceptionRegistry.keyFor(method);
    }

    @Test
    public void withoutRegistrationsOriginalRuns() throws Throwable {
        InterceptionRegistry registry = new InterceptionRegistry();
        Object result = registry.dispatch(key(), null, new Object[] {"world"},
                args -> "hello " + args[0]);
        assertEquals("hello world", result);
    }

    @Test
    public void beforeCanReplaceResultAndSkipOriginal() throws Throwable {
        InterceptionRegistry registry = new InterceptionRegistry();
        final boolean[] originalCalled = {false};
        registry.register(key(), new HookCallback() {
            @Override
            public void before(HookParam param) {
                param.setResult("hooked:" + param.arg(0));
            }
        });
        Object result = registry.dispatch(key(), null, new Object[] {"world"}, args -> {
            originalCalled[0] = true;
            return "hello " + args[0];
        });
        assertEquals("hooked:world", result);
        assertFalse("Original call must be skipped", originalCalled[0]);
    }

    @Test
    public void beforeCanModifyArguments() throws Throwable {
        InterceptionRegistry registry = new InterceptionRegistry();
        registry.register(key(), new HookCallback() {
            @Override
            public void before(HookParam param) {
                param.setArg(0, "changed");
            }
        });
        Object result = registry.dispatch(key(), null, new Object[] {"original"},
                args -> "hello " + args[0]);
        assertEquals("hello changed", result);
    }

    @Test
    public void afterCanRewriteResult() throws Throwable {
        InterceptionRegistry registry = new InterceptionRegistry();
        registry.register(key(), new HookCallback() {
            @Override
            public void after(HookParam param) {
                param.setResult(param.result() + "!");
            }
        });
        Object result = registry.dispatch(key(), null, new Object[] {"world"},
                args -> "hello " + args[0]);
        assertEquals("hello world!", result);
    }

    @Test
    public void originalExceptionIsVisibleAndReplaceable() throws Throwable {
        InterceptionRegistry registry = new InterceptionRegistry();
        registry.register(key(), new HookCallback() {
            @Override
            public void after(HookParam param) {
                if (param.throwable() != null) {
                    param.setThrowable(new IllegalArgumentException("wrapped"));
                }
            }
        });
        try {
            registry.dispatch(key(), null, new Object[] {"x"}, args -> {
                throw new IllegalStateException("original failure");
            });
            throw new AssertionError("expected exception");
        } catch (IllegalArgumentException e) {
            assertEquals("wrapped", e.getMessage());
        }
    }

    @Test
    public void beforeCanThrowInsteadOfReturning() throws Throwable {
        InterceptionRegistry registry = new InterceptionRegistry();
        registry.register(key(), new HookCallback() {
            @Override
            public void before(HookParam param) {
                param.setThrowable(new SecurityException("denied"));
            }
        });
        try {
            registry.dispatch(key(), null, new Object[] {"x"}, args -> "hello");
            throw new AssertionError("expected exception");
        } catch (SecurityException e) {
            assertEquals("denied", e.getMessage());
        }
    }

    /** Порядок — порядок регистрации: платформа не обещает приоритизацию. */
    @Test
    public void multipleHooksRunInRegistrationOrder() throws Throwable {
        InterceptionRegistry registry = new InterceptionRegistry();
        List<String> order = new ArrayList<>();
        registry.register(key(), new HookCallback() {
            @Override
            public void after(HookParam param) {
                order.add("first");
            }
        });
        registry.register(key(), new HookCallback() {
            @Override
            public void after(HookParam param) {
                order.add("second");
            }
        });
        registry.dispatch(key(), null, new Object[] {"x"}, args -> "hello");
        assertEquals(List.of("first", "second"), order);
        assertEquals(2, registry.activeCount(key()));
    }

    @Test
    public void firstImmediateReturnStopsChain() throws Throwable {
        InterceptionRegistry registry = new InterceptionRegistry();
        final boolean[] secondCalled = {false};
        registry.register(key(), new HookCallback() {
            @Override
            public void before(HookParam param) {
                param.setResult("first-wins");
            }
        });
        registry.register(key(), new HookCallback() {
            @Override
            public void before(HookParam param) {
                secondCalled[0] = true;
            }
        });
        Object result = registry.dispatch(key(), null, new Object[] {"x"}, args -> "original");
        assertEquals("first-wins", result);
        assertFalse(secondCalled[0]);
    }

    @Test
    public void unhookRemovesInterceptor() throws Throwable {
        InterceptionRegistry registry = new InterceptionRegistry();
        HookHandle handle = registry.register(key(), new HookCallback() {
            @Override
            public void before(HookParam param) {
                param.setResult("hooked");
            }
        });
        assertTrue(registry.hasRegistrations(key()));
        assertTrue(handle.unhook());
        assertFalse("Second unhook must report false", handle.unhook());
        assertFalse(registry.hasRegistrations(key()));
        Object result = registry.dispatch(key(), null, new Object[] {"x"}, args -> "original");
        assertEquals("original", result);
    }

    @Test
    public void clearRemovesEverything() throws Throwable {
        InterceptionRegistry registry = new InterceptionRegistry();
        registry.register(key(), new HookCallback() {
        });
        registry.clear();
        assertEquals(0, registry.activeCount(key()));
        assertEquals("original", registry.dispatch(key(), null, new Object[] {"x"},
                args -> "original"));
    }

    /** Backend на реестре даёт модулю явный результат, а не молчаливый no-op. */
    @Test
    public void registryBackendReportsExplicitResults() throws Exception {
        InterceptionRegistry registry = new InterceptionRegistry();
        RegistryHookBackend backend = new RegistryHookBackend(registry);
        assertTrue(backend.isAvailable());
        assertEquals(RegistryHookBackend.NAME, backend.name());

        Method target = FakeModule.InterceptedTarget.class.getMethod("greeting", String.class);
        HookResult ok = backend.hook(target, new HookCallback() {
        });
        assertTrue(ok.isSuccess());
        assertTrue(ok.handle().targetDescription().contains("greeting"));

        HookResult nullTarget = backend.hook(null, new HookCallback() {
        });
        assertFalse(nullTarget.isSuccess());
        assertEquals(HookResult.ERR_TARGET_NOT_FOUND, nullTarget.errorCode());

        HookResult nullCallback = backend.hook(target, null);
        assertEquals(HookResult.ERR_UNSUPPORTED_TARGET, nullCallback.errorCode());
    }

    /** Честный отказ, когда backend отсутствует (ТЗ §27). */
    @Test
    public void unavailableBackendNeverPretendsToHook() throws Exception {
        UnavailableHookBackend backend = new UnavailableHookBackend("нет backend для этой версии");
        assertFalse(backend.isAvailable());
        Method target = FakeModule.InterceptedTarget.class.getMethod("greeting", String.class);
        HookResult result = backend.hook(target, new HookCallback() {
        });
        assertFalse(result.isSuccess());
        assertEquals(HookResult.ERR_BACKEND_UNAVAILABLE, result.errorCode());
        assertTrue(result.message().contains("нет backend"));
        assertTrue(backend.unavailabilityReason().contains("backend"));
    }
}
