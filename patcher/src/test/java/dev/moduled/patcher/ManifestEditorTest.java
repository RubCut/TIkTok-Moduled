package dev.moduled.patcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.moduled.core.Result;
import dev.moduled.patcher.xml.BinaryXmlReader;
import dev.moduled.patcher.xml.XmlDocument;
import dev.moduled.patcher.xml.XmlElement;
import org.junit.Test;

/**
 * ТЗ §7.2 PAT-08/PAT-09/PAT-10 и §15.2: правка manifest меняет только точку
 * запуска и добавляет экран восстановления; launcher, authorities, providers и
 * остальные атрибуты сохраняются; повторный патч идемпотентен.
 */
public class ManifestEditorTest {

    private static final String PACKAGE = "com.example.host";
    private static final String LAUNCHER = "com.example.host.LauncherActivity";
    private static final String AUTHORITIES = "com.example.host.fileprovider";

    private byte[] baseManifest() {
        return TestAxml.manifest(PACKAGE, 30430000, "30.4.3",
                "com.example.host.HostApplication", AUTHORITIES, LAUNCHER, 28, 35);
    }

    @Test
    public void parsesGeneratedManifest() {
        Result<XmlDocument> parsed = BinaryXmlReader.parse(baseManifest());
        assertTrue(parsed.errorMessage(), parsed.isOk());
        XmlElement root = parsed.value().root;
        assertEquals("manifest", root.name);
        assertEquals(PACKAGE, root.stringAttribute("package"));
        assertEquals(Integer.valueOf(30430000), root.intAttribute("versionCode"));
        XmlElement application = root.firstChild("application");
        assertNotNull(application);
        assertEquals("com.example.host.HostApplication", application.stringAttribute("name"));
        assertEquals(1, application.descendantsNamed("provider").size());
        assertEquals(AUTHORITIES,
                application.descendantsNamed("provider").get(0).stringAttribute("authorities"));
        assertFalse(application.descendantsNamed("activity").isEmpty());
    }

    @Test
    public void setsEntryPointAndKeepsEverythingElse() {
        Result<ManifestEditor.EditResult> edit = ManifestEditor.apply(baseManifest(),
                ManifestEditor.RUNTIME_APPLICATION, ManifestEditor.SAFE_MODE_ACTIVITY);
        assertTrue(edit.errorMessage(), edit.isOk());

        Result<XmlDocument> parsed = BinaryXmlReader.parse(edit.value().manifestBytes);
        assertTrue(parsed.errorMessage(), parsed.isOk());
        XmlElement root = parsed.value().root;

        // Точка запуска заменена (PAT-08).
        XmlElement application = root.firstChild("application");
        assertEquals(ManifestEditor.RUNTIME_APPLICATION, application.stringAttribute("name"));

        // Package ID и версия не изменились (PAT-09: ничего лишнего).
        assertEquals(PACKAGE, root.stringAttribute("package"));
        assertEquals("30.4.3", root.stringAttribute("versionName"));

        // Launcher, authorities и provider сохранены (PAT-10).
        boolean launcherFound = false;
        for (XmlElement activity : application.descendantsNamed("activity")) {
            if (LAUNCHER.equals(activity.stringAttribute("name"))) {
                launcherFound = true;
                assertEquals("true", activity.stringAttribute("exported"));
            }
        }
        assertTrue("launcher-компонент обязан сохраниться", launcherFound);
        assertEquals(AUTHORITIES,
                application.descendantsNamed("provider").get(0).stringAttribute("authorities"));

        // Экран восстановления объявлен и не экспортирован (§20).
        XmlElement recovery = null;
        for (XmlElement activity : application.descendantsNamed("activity")) {
            if (ManifestEditor.SAFE_MODE_ACTIVITY.equals(activity.stringAttribute("name"))) {
                recovery = activity;
            }
        }
        assertNotNull("экран восстановления safe mode обязан быть объявлен", recovery);
        assertEquals("false", recovery.stringAttribute("exported"));

        // Diff непустой и описывает замену имени (§15.2).
        assertFalse(edit.value().changes.isEmpty());
        String diff = ManifestEditor.diffText(edit.value().changes);
        assertTrue(diff.contains("android:name"));
        assertTrue(diff.contains("com.example.host.HostApplication"));
    }

    @Test
    public void repatchIsIdempotent() {
        Result<ManifestEditor.EditResult> first = ManifestEditor.apply(baseManifest(),
                ManifestEditor.RUNTIME_APPLICATION, ManifestEditor.SAFE_MODE_ACTIVITY);
        assertTrue(first.isOk());
        Result<ManifestEditor.EditResult> second = ManifestEditor.apply(
                first.value().manifestBytes,
                ManifestEditor.RUNTIME_APPLICATION, ManifestEditor.SAFE_MODE_ACTIVITY);
        assertTrue(second.errorMessage(), second.isOk());
        // PAT-11: повторная правка не добавляет ни изменений, ни второго экрана.
        assertTrue("повторный патч не должен менять manifest", second.value().changes.isEmpty());
        Result<XmlDocument> parsed = BinaryXmlReader.parse(second.value().manifestBytes);
        assertTrue(parsed.isOk());
        XmlElement application = parsed.value().root.firstChild("application");
        int recoveryCount = 0;
        for (XmlElement activity : application.descendantsNamed("activity")) {
            if (ManifestEditor.SAFE_MODE_ACTIVITY.equals(activity.stringAttribute("name"))) {
                recoveryCount++;
            }
        }
        assertEquals(1, recoveryCount);
    }

    @Test
    public void rejectsGarbageManifest() {
        byte[] garbage = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        Result<ManifestEditor.EditResult> edit = ManifestEditor.apply(garbage,
                ManifestEditor.RUNTIME_APPLICATION, ManifestEditor.SAFE_MODE_ACTIVITY);
        assertFalse(edit.isOk());
        assertNotNull(edit.errorCode());
    }
}
