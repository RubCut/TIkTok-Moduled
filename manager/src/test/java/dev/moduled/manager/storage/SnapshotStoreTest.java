package dev.moduled.manager.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.moduled.core.Result;
import dev.moduled.core.Snapshot;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** §20.2: поколения снимков, атомарная публикация и откат к предыдущему целому. */
public class SnapshotStoreTest {

    private static final String HASH = "a".repeat(64);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File root;
    private SnapshotStore snapshots;
    private ModuleStore modules;

    @Before
    public void setUp() throws IOException {
        root = tmp.newFolder("storage").toPath().resolve("modules").toFile();
        snapshots = new SnapshotStore(root);
        modules = new ModuleStore(root);
    }

    private static Snapshot snapshot(long generation, boolean enabled) {
        return new Snapshot(generation, System.currentTimeMillis(), List.of(
                new Snapshot.ModuleEntry("sample.module", 1, HASH, HASH, enabled)));
    }

    @Test
    public void publishesNewGeneration() {
        assertEquals(0, snapshots.currentGeneration());
        Result<Long> first = snapshots.publish(snapshot(1, true));
        assertTrue(first.errorMessage(), first.isOk());
        assertEquals(1L, (long) first.value());
        assertEquals(1, snapshots.currentGeneration());

        Result<Long> second = snapshots.publish(snapshot(2, false));
        assertTrue(second.isOk());
        assertEquals(2, snapshots.currentGeneration());
        assertEquals(1, snapshots.previous().value().generation);
    }

    @Test
    public void rejectsStaleGeneration() {
        assertTrue(snapshots.publish(snapshot(5, true)).isOk());
        Result<Long> stale = snapshots.publish(snapshot(5, true));
        assertEquals("SNAPSHOT_GENERATION_NOT_NEWER", stale.errorCode());
        assertTrue(snapshots.publish(snapshot(4, true)).errorCode()
                .equals("SNAPSHOT_GENERATION_NOT_NEWER"));
        assertEquals("Активный снимок не меняется после отказа", 5, snapshots.currentGeneration());
    }

    @Test
    public void rejectsSnapshotWithBadHashes() {
        Snapshot bad = new Snapshot(1, 0L, List.of(
                new Snapshot.ModuleEntry("sample.module", 1, "not-a-hash", HASH, true)));
        assertEquals("SNAPSHOT_BAD_HASH", snapshots.publish(bad).errorCode());
    }

    /** §20.2: повреждённое поколение не используется — читается предыдущее. */
    @Test
    public void corruptedActiveFallsBackToPrevious() throws IOException {
        assertTrue(snapshots.publish(snapshot(1, true)).isOk());
        assertTrue(snapshots.publish(snapshot(2, true)).isOk());
        File active = new File(new File(root, "snapshots"), "active.txt");
        Files.write(active.toPath(), "повреждённый снимок".getBytes(StandardCharsets.UTF_8));

        Result<Snapshot> read = snapshots.active();
        assertTrue(read.errorMessage(), read.isOk());
        assertEquals(1, read.value().generation);
    }

    @Test
    public void missingActiveIsReported() {
        Result<Snapshot> read = snapshots.active();
        assertFalse(read.isOk());
        assertEquals("SNAPSHOT_MISSING", read.errorCode());
    }

    @Test
    public void buildsNextSnapshotFromStore() throws IOException {
        File archive = TestModulePackage.buildValid(tmp.newFolder("packages"), "sample.zip",
                "sample.module", "1.0.0", 1);
        assertTrue(modules.install(InstallRequest.fromFile(archive).build()).isOk());

        Result<Snapshot> built = snapshots.buildNext(modules);
        assertTrue(built.errorMessage(), built.isOk());
        assertEquals(1, built.value().generation);
        assertEquals(1, built.value().modules.size());
        assertFalse("Новый модуль выключен, поэтому в снимке он неактивен",
                built.value().modules.get(0).enabled);

        assertTrue(modules.setEnabled("sample.module", true).isOk());
        Snapshot enabled = snapshots.buildNext(modules).value();
        assertTrue(enabled.modules.get(0).enabled);
        assertEquals(64, enabled.modules.get(0).dexSha256.length());
        assertTrue(snapshots.publish(enabled).isOk());
        assertEquals(1, snapshots.currentGeneration());
    }

    @Test
    public void activeJsonIsAvailableForBridge() {
        assertTrue(snapshots.publish(snapshot(3, true)).isOk());
        Result<String> json = snapshots.activeJson();
        assertTrue(json.isOk());
        assertTrue(json.value().contains("\"generation\":3"));
    }
}
