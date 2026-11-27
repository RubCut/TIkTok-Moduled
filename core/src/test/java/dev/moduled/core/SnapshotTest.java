package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** §20.2: снимок согласован, неизменяем и читается в детерминированном порядке. */
public class SnapshotTest {

    private static Snapshot.ModuleEntry entry(String id, boolean enabled) {
        return new Snapshot.ModuleEntry(id, 1, "dex-" + id, "cfg-" + id, enabled);
    }

    @Test
    public void activeModulesAreSortedById() {
        Snapshot snapshot = new Snapshot(7, 1000L, List.of(
                entry("zeta.module", true),
                entry("alpha.module", true),
                entry("disabled.module", false),
                entry("mid.module", true)));
        List<String> ids = new ArrayList<>();
        for (Snapshot.ModuleEntry e : snapshot.activeSortedById()) {
            ids.add(e.id);
        }
        assertEquals(List.of("alpha.module", "mid.module", "zeta.module"), ids);
    }

    @Test
    public void snapshotIsImmutable() {
        List<Snapshot.ModuleEntry> modules = new ArrayList<>();
        modules.add(entry("a.module", true));
        Snapshot snapshot = new Snapshot(1, 0L, modules);
        modules.add(entry("injected.module", true));
        assertEquals(1, snapshot.modules.size());
        try {
            snapshot.modules.add(entry("hack.module", true));
            throw new AssertionError("modules list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // ожидаемо
        }
    }

    @Test
    public void sameGenerationReadsSameContent() {
        Snapshot snapshot = new Snapshot(3, 42L, List.of(entry("a.module", true)));
        assertSame(snapshot.activeSortedById().get(0), snapshot.modules.get(0));
        assertEquals(3, snapshot.generation);
        assertTrue(snapshot.activeSortedById().size() == 1);
    }
}
