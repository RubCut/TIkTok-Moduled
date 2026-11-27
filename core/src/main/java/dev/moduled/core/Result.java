package dev.moduled.core;

import java.util.Objects;

/**
 * Результат операции с явной ошибкой вместо исключений в API ядра.
 * Используется везде, где ТЗ требует понятного отказа (патчер, установка, валидация).
 */
public final class Result<T> {
    private final T value;
    private final String errorCode;
    private final String errorMessage;

    private Result(T value, String errorCode, String errorMessage) {
        this.value = value;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static <T> Result<T> ok(T value) {
        return new Result<>(value, null, null);
    }

    public static <T> Result<T> fail(String errorCode, String errorMessage) {
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(errorMessage, "errorMessage");
        return new Result<>(null, errorCode, errorMessage);
    }

    public boolean isOk() {
        return errorCode == null;
    }

    public T value() {
        if (!isOk()) {
            throw new IllegalStateException("Result is failure: " + errorCode + ": " + errorMessage);
        }
        return value;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return isOk() ? "Ok(" + value + ")" : "Fail(" + errorCode + ": " + errorMessage + ")";
    }
}
