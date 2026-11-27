package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** §20.1/§20.2: authority, permission и запрет выбирать произвольный файл из IPC. */
public class BridgeContractTest {

    @Test
    public void authorityAndPermissionAreFixed() {
        assertEquals("dev.moduled.manager.bridge", BridgeContract.AUTHORITY);
        assertEquals("dev.moduled.manager.permission.BRIDGE", BridgeContract.PERMISSION);
        assertEquals("signature", BridgeContract.PERMISSION_PROTECTION);
    }

    @Test
    public void buildsContentUris() {
        assertEquals("content://dev.moduled.manager.bridge/dex/region.module",
                BridgeContract.dexUri("region.module"));
        assertEquals("content://dev.moduled.manager.bridge/config/region.module",
                BridgeContract.configUri("region.module"));
        assertTrue(BridgeContract.reportUri("crash-123.txt")
                .startsWith("content://dev.moduled.manager.bridge/report/"));
    }

    @Test
    public void rejectsTraversalInUriParameters() {
        for (String bad : new String[] {"../evil", "a/b", "..", "", "C:x", "a\\b", "UPPER"}) {
            try {
                BridgeContract.dexUri(bad);
                fail("URI must not be built for id: " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("Invalid module id"));
            }
        }
        for (String bad : new String[] {"../x", "a/b", "", "na?me"}) {
            try {
                BridgeContract.reportUri(bad);
                fail("Report URI must not be built for: " + bad);
            } catch (IllegalArgumentException expected) {
                // ожидаемо
            }
        }
    }
}
