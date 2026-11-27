package dev.moduled.core;

/**
 * Правила текстового конфига (ТЗ §12 CFG-01..CFG-04):
 * отдельный файл на модуль, UTF-8, лимит 1 MiB точных байт, содержимое непрозрачно,
 * пустой файл допустим. Платформа не делает trim/форматирование/нормализацию.
 */
public final class ConfigValidator {
    private ConfigValidator() {
    }

    public static Result<Void> validate(byte[] configBytes) {
        if (configBytes == null) {
            return Result.fail("CONFIG_NULL", "Config bytes are null");
        }
        if (configBytes.length > Limits.MAX_CONFIG_BYTES) {
            return Result.fail("CONFIG_TOO_LARGE",
                    "Config exceeds " + Limits.MAX_CONFIG_BYTES + " bytes (got " + configBytes.length + ")");
        }
        try {
            ManifestValidator.StrictUtf8.decode(configBytes);
        } catch (IllegalArgumentException e) {
            return Result.fail("CONFIG_NOT_UTF8", "Config is not valid UTF-8: " + e.getMessage());
        }
        return Result.ok(null);
    }

    /** Точное сохранение без преобразований: байты возвращаются как есть (CFG-04). */
    public static byte[] preserve(byte[] configBytes) {
        byte[] copy = new byte[configBytes.length];
        System.arraycopy(configBytes, 0, copy, 0, configBytes.length);
        return copy;
    }
}
