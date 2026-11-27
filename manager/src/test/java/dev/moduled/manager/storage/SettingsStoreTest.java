package dev.moduled.manager.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** §21.2 экран 9 и §18.3: настройки хранятся атомарно и читаются после сбоя. */
public class SettingsStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void defaultsAreSafe() throws IOException {
        SettingsStore settings = new SettingsStore(new File(tmp.newFolder("s"), "settings.txt"));
        assertEquals("SYSTEM", settings.getString(SettingsStore.KEY_THEME, "SYSTEM"));
        assertFalse("Автокопирование логов по умолчанию выключено",
                settings.autoCopyCrashReport());
        assertEquals(SettingsStore.LogPrivacy.MINIMAL, settings.logPrivacy());
    }

    @Test
    public void valuesSurviveReload() throws IOException {
        File file = new File(tmp.newFolder("s2"), "settings.txt");
        SettingsStore settings = new SettingsStore(file);
        assertTrue(settings.setString(SettingsStore.KEY_THEME, "DARK").isOk());
        assertTrue(settings.setBoolean(SettingsStore.KEY_AUTO_COPY_CRASH, true).isOk());
        assertTrue(settings.setLogPrivacy(SettingsStore.LogPrivacy.VERBOSE).isOk());
        assertTrue(settings.setLong(SettingsStore.KEY_LAST_CATALOG_UPDATE, 12345L).isOk());

        SettingsStore reloaded = new SettingsStore(file);
        assertEquals("DARK", reloaded.getString(SettingsStore.KEY_THEME, "SYSTEM"));
        assertTrue(reloaded.autoCopyCrashReport());
        assertEquals(SettingsStore.LogPrivacy.VERBOSE, reloaded.logPrivacy());
        assertEquals(12345L, reloaded.getLong(SettingsStore.KEY_LAST_CATALOG_UPDATE, 0));
    }

    @Test
    public void corruptedFileFallsBackToDefaults() throws IOException {
        File file = new File(tmp.newFolder("s3"), "settings.txt");
        Files.write(file.toPath(), "мусор\nбез знаков равенства\n".getBytes(StandardCharsets.UTF_8));
        SettingsStore settings = new SettingsStore(file);
        assertEquals("LIGHT", settings.getString(SettingsStore.KEY_THEME, "LIGHT"));
        assertEquals(SettingsStore.LogPrivacy.MINIMAL, settings.logPrivacy());
        // Запись после повреждения восстанавливает файл.
        assertTrue(settings.setString(SettingsStore.KEY_THEME, "DARK").isOk());
        assertEquals("DARK", new SettingsStore(file).getString(SettingsStore.KEY_THEME, "SYSTEM"));
    }

    @Test
    public void newlinesInValuesDoNotBreakFormat() throws IOException {
        File file = new File(tmp.newFolder("s4"), "settings.txt");
        SettingsStore settings = new SettingsStore(file);
        assertTrue(settings.setString("note", "первая\nвторая").isOk());
        assertEquals("первая вторая", new SettingsStore(file).getString("note", ""));
    }
}
