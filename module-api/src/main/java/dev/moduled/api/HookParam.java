package dev.moduled.api;

import java.util.Arrays;

/**
 * Параметры одного перехваченного вызова. Класс намеренно содержит логику,
 * проверяемую обычными JVM-тестами: замена результата, подмена исключения,
 * доступ к аргументам.
 */
public final class HookParam {

    private final Object thisObject;
    private final Object[] args;

    private Object result;
    private Throwable throwable;
    private boolean returnsImmediately;

    public HookParam(Object thisObject, Object[] args) {
        this.thisObject = thisObject;
        this.args = args != null ? args.clone() : new Object[0];
    }

    /** Экземпляр, на котором вызывается метод, или null для статических методов. */
    public Object thisObject() {
        return thisObject;
    }

    /** Аргументы вызова; изменение массива влияет на оригинальный вызов. */
    public Object[] args() {
        return args;
    }

    public Object arg(int index) {
        if (index < 0 || index >= args.length) {
            throw new IndexOutOfBoundsException(
                    "Argument index " + index + " out of range 0.." + (args.length - 1));
        }
        return args[index];
    }

    public void setArg(int index, Object value) {
        if (index < 0 || index >= args.length) {
            throw new IndexOutOfBoundsException(
                    "Argument index " + index + " out of range 0.." + (args.length - 1));
        }
        args[index] = value;
    }

    /** Результат оригинального (или подменённого) вызова. */
    public Object result() {
        return result;
    }

    /**
     * Подменяет результат и отменяет вызов оригинального метода, если сделано в
     * {@link HookCallback#before(HookParam)}.
     */
    public void setResult(Object value) {
        this.result = value;
        this.throwsImmediately = false;
        this.returnsImmediately = true;
    }

    /** Исключение, брошенное вызовом, либо подменённое модулем. */
    public Throwable throwable() {
        return throwable;
    }

    /**
     * Подменяет результат исключением. В {@code before} отменяет оригинальный
     * вызов; в {@code after} заменяет исход исключения.
     */
    public void setThrowable(Throwable value) {
        this.throwable = value;
        this.returnsImmediately = true;
    }

    /**
     * True, если {@code before} потребовал не вызывать оригинальный метод:
     * вместо него возвращается {@link #result()} или бросается {@link #throwable()}.
     */
    public boolean returnsImmediately() {
        return returnsImmediately;
    }

    /** Сброс требования немедленного возврата (используется runtime при ошибках). */
    public void clearImmediateReturn() {
        this.returnsImmediately = false;
    }

    @Override
    public String toString() {
        return "HookParam{this=" + thisObject + ", args=" + Arrays.toString(args)
                + ", result=" + result + ", throwable=" + throwable
                + ", returnsImmediately=" + returnsImmediately + '}';
    }
}
