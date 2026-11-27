package dev.moduled.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ТЗ §9.1: передача аргументов, изменение результата и обработка исключения. */
public class HookParamTest {

    @Test
    public void exposesThisObjectAndArguments() {
        Object target = new Object();
        HookParam param = new HookParam(target, new Object[] {"a", 2, null});
        assertSame(target, param.thisObject());
        assertEquals(3, param.args().length);
        assertEquals("a", param.arg(0));
        assertEquals(2, param.arg(1));
        assertNull(param.arg(2));
    }

    @Test
    public void modifiesArgumentsInPlace() {
        HookParam param = new HookParam(null, new Object[] {"original"});
        param.setArg(0, "replaced");
        assertEquals("replaced", param.arg(0));
        assertEquals("replaced", param.args()[0]);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void rejectsArgumentIndexAboveRange() {
        new HookParam(null, new Object[] {"only"}).arg(1);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void rejectsNegativeArgumentIndex() {
        new HookParam(null, new Object[] {"only"}).setArg(-1, "x");
    }

    @Test
    public void nullArgumentsBecomeEmptyArray() {
        HookParam param = new HookParam(null, null);
        assertEquals(0, param.args().length);
    }

    /** Замена результата в before() отменяет вызов оригинального метода. */
    @Test
    public void setResultCancelsOriginalCall() {
        HookParam param = new HookParam(null, new Object[0]);
        assertFalse(param.returnsImmediately());
        param.setResult("hooked");
        assertTrue(param.returnsImmediately());
        assertEquals("hooked", param.result());
        assertNull(param.throwable());
    }

    @Test
    public void setThrowableReplacesResult() {
        HookParam param = new HookParam(null, new Object[0]);
        param.setResult("value");
        IllegalStateException error = new IllegalStateException("boom");
        param.setThrowable(error);
        assertSame(error, param.throwable());
        assertTrue(param.returnsImmediately());
    }

    @Test
    public void clearImmediateReturnRestoresOriginalCall() {
        HookParam param = new HookParam(null, new Object[0]);
        param.setResult("hooked");
        param.clearImmediateReturn();
        assertFalse(param.returnsImmediately());
    }

    @Test
    public void argumentArrayIsDefensivelyCopied() {
        Object[] source = {"a"};
        HookParam param = new HookParam(null, source);
        source[0] = "mutated-after-construction";
        assertEquals("a", param.arg(0));
    }
}
