package dev.moduled.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ConfigValidatorTest {

    /** CFG-01: лимит 1 MiB точных байт; ровно лимит допустим, лимит+1 — отказ. */
    @Test
    public void enforcesExactOneMebibyteLimit() {
        byte[] atLimit = new byte[Limits.MAX_CONFIG_BYTES];
        java.util.Arrays.fill(atLimit, (byte) 'a');
        assertTrue(ConfigValidator.validate(atLimit).isOk());

        byte[] overLimit = new byte[Limits.MAX_CONFIG_BYTES + 1];
        java.util.Arrays.fill(overLimit, (byte) 'a');
        Result<Void> result = ConfigValidator.validate(overLimit);
        assertTrue(!result.isOk());
        assertEquals("CONFIG_TOO_LARGE", result.errorCode());
    }

    /** CFG-04: пустой файл допустим и остаётся пустым. */
    @Test
    public void allowsEmptyConfig() {
        assertTrue(ConfigValidator.validate(new byte[0]).isOk());
        assertArrayEquals(new byte[0], ConfigValidator.preserve(new byte[0]));
    }

    /** CFG-04: UTF-8 обязателен, недопустимые последовательности отклоняются. */
    @Test
    public void rejectsInvalidUtf8() {
        byte[] invalid = new byte[] {'a', (byte) 0xC3, (byte) 0x28, 'z'};
        Result<Void> result = ConfigValidator.validate(invalid);
        assertTrue(!result.isOk());
        assertEquals("CONFIG_NOT_UTF8", result.errorCode());
    }

    /** CFG-03: содержимое непрозрачно — BOM, CRLF и пробелы сохраняются байт в байт. */
    @Test
    public void preservesBytesExactly() {
        byte[] original = "\uFEFFkey = value\r\n  spaced  \r\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(ConfigValidator.validate(original).isOk());
        assertArrayEquals(original, ConfigValidator.preserve(original));
        assertEquals(new String(original, StandardCharsets.UTF_8),
                new String(ConfigValidator.preserve(original), StandardCharsets.UTF_8));
    }
}
