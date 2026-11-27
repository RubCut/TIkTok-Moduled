package dev.moduled.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** §13.3: сравнение по целому versionCode, а не лексикографически по version. */
public class VersionCompareTest {

    @Test
    public void comparesByNumericVersionCode() {
        assertTrue(VersionCompare.compareVersionCodes(9, 10) < 0);
        assertTrue(VersionCompare.compareVersionCodes(10, 9) > 0);
        assertTrue(VersionCompare.compareVersionCodes(10, 10) == 0);
    }

    @Test
    public void lexicographicTrapIsAvoided() {
        // "1.10.0" лексикографически меньше "1.9.0", но versionCode решает.
        long newer = 1100;
        long older = 1090;
        assertTrue(VersionCompare.isUpdate(older, newer));
        assertFalse(VersionCompare.isUpdate(newer, older));
        assertTrue(VersionCompare.isDowngrade(newer, older));
        assertFalse(VersionCompare.isDowngrade(older, newer));
        assertFalse(VersionCompare.isUpdate(10, 10));
        assertFalse(VersionCompare.isDowngrade(10, 10));
    }
}
