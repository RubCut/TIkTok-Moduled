package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** §20.2: читатель видит целиком старое или целиком новое поколение. */
public class SnapshotCodecTest {

    private static final String HASH_A = "0".repeat(63) + "a";
    private static final String HASH_B = "1".repeat(64);

    private static Snapshot sample() {
        return new Snapshot(7, 1_700_000_000_000L, List.of(
                new Snapshot.ModuleEntry("region.module", 12, HASH_A, HASH_B, true),
                new Snapshot.ModuleEntry("sample.module", 1, HASH_B, HASH_A, false)));
    }

    @Test
    public void roundTripPreservesSnapshot() {
        Snapshot original = sample();
        String json = SnapshotCodec.encode(original);
        Result<Snapshot> decoded = SnapshotCodec.decode(json);
        assertTrue(decoded.errorMessage(), decoded.isOk());
        Snapshot value = decoded.value();
        assertEquals(original.generation, value.generation);
        assertEquals(original.createdWallMs, value.createdWallMs);
        assertEquals(2, value.modules.size());
        assertEquals("region.module", value.modules.get(0).id);
        assertEquals(12L, value.modules.get(0).versionCode);
        assertEquals(HASH_A, value.modules.get(0).dexSha256);
        assertTrue(value.modules.get(0).enabled);
        assertFalse(value.modules.get(1).enabled);
    }

    @Test
    public void encodesEmptyModuleList() {
        Snapshot empty = new Snapshot(1, 0L, List.of());
        Result<Snapshot> decoded = SnapshotCodec.decode(SnapshotCodec.encode(empty));
        assertTrue(decoded.isOk());
        assertTrue(decoded.value().modules.isEmpty());
    }

    @Test
    public void rejectsMalformedSnapshots() {
        assertEquals("SNAPSHOT_EMPTY", SnapshotCodec.decode(null).errorCode());
        assertEquals("SNAPSHOT_EMPTY", SnapshotCodec.decode("").errorCode());
        assertEquals("SNAPSHOT_BAD_JSON", SnapshotCodec.decode("{oops").errorCode());
        assertEquals("SNAPSHOT_NOT_OBJECT", SnapshotCodec.decode("[1,2]").errorCode());
    }

    @Test
    public void rejectsUnknownFieldsAndUnsupportedSchema() {
        String json = SnapshotCodec.encode(sample());
        assertEquals("SNAPSHOT_UNKNOWN_FIELD",
                SnapshotCodec.decode(json.replace("\"generation\":7", "\"generation\":7,\"future\":1"))
                        .errorCode());
        assertEquals("SNAPSHOT_UNSUPPORTED_SCHEMA",
                SnapshotCodec.decode(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
                        .errorCode());
    }

    @Test
    public void rejectsBadGenerationAndHashes() {
        String json = SnapshotCodec.encode(sample());
        assertEquals("SNAPSHOT_BAD_GENERATION",
                SnapshotCodec.decode(json.replace("\"generation\":7", "\"generation\":0")).errorCode());
        assertEquals("SNAPSHOT_BAD_HASH",
                SnapshotCodec.decode(json.replace(HASH_A, "not-a-hash")).errorCode());
        assertEquals("SNAPSHOT_BAD_VERSION_CODE",
                SnapshotCodec.decode(json.replace("\"versionCode\":12", "\"versionCode\":-1")).errorCode());
        assertEquals("SNAPSHOT_BAD_ENABLED",
                SnapshotCodec.decode(json.replace("\"enabled\":true", "\"enabled\":\"true\"")).errorCode());
    }

    @Test
    public void rejectsDuplicateModuleIds() {
        Snapshot duplicate = new Snapshot(2, 0L, List.of(
                new Snapshot.ModuleEntry("a.module", 1, HASH_A, HASH_A, true),
                new Snapshot.ModuleEntry("a.module", 2, HASH_A, HASH_A, true)));
        assertEquals("SNAPSHOT_DUPLICATE_MODULE",
                SnapshotCodec.decode(SnapshotCodec.encode(duplicate)).errorCode());
    }

    @Test
    public void rejectsBadModuleId() {
        Snapshot bad = new Snapshot(2, 0L, List.of(
                new Snapshot.ModuleEntry("../evil", 1, HASH_A, HASH_A, true)));
        assertEquals("SNAPSHOT_BAD_MODULE_ID",
                SnapshotCodec.decode(SnapshotCodec.encode(bad)).errorCode());
    }

    /** JSON-экранирование не должно ломать ID или хеши. */
    @Test
    public void quotesAreEscapedSafely() {
        Snapshot snapshot = new Snapshot(3, 0L, List.of(
                new Snapshot.ModuleEntry("a.b_module", 1, HASH_A, HASH_B, true)));
        String json = SnapshotCodec.encode(snapshot);
        assertFalse(json.contains("\n"));
        assertTrue(SnapshotCodec.decode(json).isOk());
    }
}
