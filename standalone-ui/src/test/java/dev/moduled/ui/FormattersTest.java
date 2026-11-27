package dev.moduled.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Форматирование значений экрана: честные числа, без фиктивных процентов. */
public class FormattersTest {

    @Test
    public void formatsAge() {
        assertEquals("неизвестно", Formatters.humanAge(-1));
        assertEquals("только что", Formatters.humanAge(0));
        assertEquals("42 с назад", Formatters.humanAge(42_000));
        assertEquals("5 мин назад", Formatters.humanAge(5 * 60_000));
        assertEquals("3 ч назад", Formatters.humanAge(3 * 3600_000L));
        assertEquals("2 дн назад", Formatters.humanAge(2 * 24 * 3600_000L));
    }

    @Test
    public void formatsSizeAndKeepsExactBytes() {
        assertEquals("512 Б", Formatters.humanSize(512));
        assertEquals("1.0 КиБ", Formatters.humanSize(1024));
        assertEquals("1.0 МиБ", Formatters.humanSize(1024 * 1024));
        assertEquals("1.0 МиБ (1048576 байт)", Formatters.sizeWithBytes(1024 * 1024));
        assertEquals("неизвестно", Formatters.humanSize(-5));
    }

    @Test
    public void formatsHashes() {
        String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertEquals("ba7816bf…f20015ad", Formatters.shortHash(hash));
        assertEquals("—", Formatters.shortHash(null));
        assertEquals("—", Formatters.shortHash("short"));
        String grouped = Formatters.groupedHash(hash);
        assertTrue(grouped.contains(" "));
        assertEquals(hash.length() + hash.length() / 8 - 1, grouped.length());
        assertEquals("—", Formatters.groupedHash(""));
    }

    @Test
    public void pendingRestartIsExplicit() {
        assertEquals("Применится после перезапуска", Formatters.pendingRestart(true));
        assertEquals("", Formatters.pendingRestart(false));
    }

    /** §7.5: проценты только из реального числа шагов. */
    @Test
    public void progressReflectsRealSteps() {
        assertEquals("Анализ APK — 50% (2/4)", Formatters.progress(2, 4, "Анализ APK"));
        assertEquals("Подготовка", Formatters.progress(0, 0, "Подготовка"));
        assertEquals("Финиш — 100% (4/4)", Formatters.progress(4, 4, "Финиш"));
    }

    @Test
    public void themeModeIsRecoveredFromStorage() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage(null));
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("nonsense"));
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage("DARK"));
        assertTrue(ThemeMode.DYNAMIC.allowsDynamicColors());
        assertFalse(ThemeMode.SYSTEM.allowsDynamicColors());
    }
}
