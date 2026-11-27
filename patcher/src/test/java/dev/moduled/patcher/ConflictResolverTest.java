package dev.moduled.patcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * ТЗ §7.2 PAT-06: детерминированное разрешение конфликтов записей;
 * §7.7: смена package ID — отдельный профиль; §7.8: конфликт подписи виден до установки.
 */
public class ConflictResolverTest {

    @Test
    public void keepsOriginalEntriesAndAddsNew() {
        List<ConflictResolver.Conflict> conflicts = ConflictResolver.resolve(
                Arrays.asList("AndroidManifest.xml", "classes.dex", "lib/arm64-v8a/libx.so"),
                Arrays.asList("classes.dex", "assets/tmoduled/runtime-classes.dex"),
                true);
        assertEquals(2, conflicts.size());
        // Детерминированный порядок: сортировка по имени.
        assertEquals("assets/tmoduled/runtime-classes.dex", conflicts.get(0).name);
        assertEquals(ConflictResolver.Decision.ADD_NEW, conflicts.get(0).decision);
        assertEquals("classes.dex", conflicts.get(1).name);
        assertEquals(ConflictResolver.Decision.RENAME_ADDED, conflicts.get(1).decision);
        assertTrue(conflicts.get(1).resolvedName.startsWith("classes-tmoduled-"));
        assertFalse(ConflictResolver.hasBlocking(conflicts));
    }

    @Test
    public void renamingCanBeDisabled() {
        List<ConflictResolver.Conflict> conflicts = ConflictResolver.resolve(
                Arrays.asList("classes.dex"), Arrays.asList("classes.dex"), false);
        assertEquals(ConflictResolver.Decision.KEEP_ORIGINAL, conflicts.get(0).decision);
    }

    @Test
    public void sameInputGivesSameOutput() {
        List<String> original = Arrays.asList("a.dex", "b.dex", "lib/arm64-v8a/libx.so");
        List<String> added = Arrays.asList("b.dex", "a.dex", "lib/arm64-v8a/libx.so");
        List<ConflictResolver.Conflict> first = ConflictResolver.resolve(original, added, true);
        List<ConflictResolver.Conflict> second = ConflictResolver.resolve(original, added, true);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).name, second.get(i).name);
            assertEquals(first.get(i).decision, second.get(i).decision);
            assertEquals(first.get(i).resolvedName, second.get(i).resolvedName);
        }
    }

    @Test
    public void packageIdChangeIsRejectedAsSeparateProfile() {
        ConflictResolver.Conflict conflict =
                ConflictResolver.resolvePackageId("com.example.host", "com.example.host.mod");
        assertEquals(ConflictResolver.Decision.REJECT, conflict.decision);
        assertTrue(conflict.reason.contains("отдельного профиля"));
    }

    @Test
    public void packageIdSameIsKept() {
        ConflictResolver.Conflict conflict =
                ConflictResolver.resolvePackageId("com.example.host", "com.example.host");
        assertEquals(ConflictResolver.Decision.KEEP_ORIGINAL, conflict.decision);
    }

    @Test
    public void signatureConflictIsVisibleBeforeInstall() {
        ConflictResolver.Conflict conflict = ConflictResolver.resolveSignature(
                "AA:BB:CC:DD:EE:FF:00:11:22:33", "11:22:33:44:55:66:77:88:99:AA");
        assertEquals(ConflictResolver.Decision.REJECT, conflict.decision);
        assertTrue(conflict.reason.contains("конфликт подписи"));
    }

    @Test
    public void signatureMatchAllowsUpdate() {
        ConflictResolver.Conflict conflict =
                ConflictResolver.resolveSignature("AA:BB:CC", "aa:bb:cc");
        assertEquals(ConflictResolver.Decision.KEEP_ORIGINAL, conflict.decision);
    }

    @Test
    public void unknownInstalledSignatureIsNotAConflict() {
        ConflictResolver.Conflict conflict = ConflictResolver.resolveSignature(null, "AA:BB");
        assertEquals(ConflictResolver.Decision.ADD_NEW, conflict.decision);
    }
}
