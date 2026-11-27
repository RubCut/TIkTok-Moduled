package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** TЗ §5.2/§20.1: ID модуля не должен превращаться в выход за пределы хранилища. */
public class ModulePathsTest {

    private static final Path ROOT = Paths.get("/data/user/0/dev.moduled.manager/files/modules");

    @Test
    public void derivesPathsInsideRoot() {
        Result<Path> dir = ModulePaths.moduleDir(ROOT, "region.module");
        assertTrue(dir.errorMessage(), dir.isOk());
        assertEquals(ROOT.resolve("region.module"), dir.value());
        assertTrue(ModulePaths.dexFile(ROOT, "region.module").value().endsWith("region.module/classes.dex"));
        assertTrue(ModulePaths.configFile(ROOT, "region.module").value().endsWith("region.module/config.txt"));
        assertTrue(ModulePaths.descriptorFile(ROOT, "region.module").value().endsWith("region.module/module.json"));
    }

    @Test
    public void rejectsTraversalAndInvalidIds() {
        for (String id : new String[] {"../evil", "..", ".", "a/../../b", "/abs", "a\\b",
                "Region", "dash-id", ""}) {
            Result<Path> result = ModulePaths.moduleDir(ROOT, id);
            assertTrue("id '" + id + "' must be rejected", !result.isOk());
            assertEquals("id '" + id + "' -> " + result.errorMessage(), "PATH_BAD_ID", result.errorCode());
        }
        assertEquals("PATH_NO_ROOT", ModulePaths.moduleDir(null, "a.b").errorCode());
    }

    /** Даже если ID формально прошёл бы проверку, нормализация не должна выводить за root. */
    @Test
    public void normalizedPathNeverEscapesRoot() {
        Result<Path> result = ModulePaths.moduleDir(ROOT, "a.b");
        assertTrue(result.isOk());
        assertTrue(result.value().toAbsolutePath().normalize().startsWith(ROOT.toAbsolutePath().normalize()));
    }

    @Test
    public void derivesServiceDirectories() {
        assertTrue(ModulePaths.snapshotsDir(ROOT).startsWith(ROOT));
        assertTrue(ModulePaths.activeSnapshot(ROOT).getFileName().toString().equals("active.txt"));
        assertTrue(ModulePaths.previousSnapshot(ROOT).getFileName().toString().equals("previous.txt"));
        assertTrue(ModulePaths.candidateSnapshot(ROOT).getFileName().toString().equals("candidate.txt"));
        assertTrue(ModulePaths.crashReportsDir(ROOT).startsWith(ROOT));
        assertTrue(ModulePaths.runtimeLogsDir(ROOT).startsWith(ROOT));
        assertTrue(ModulePaths.repositoryCacheDir(ROOT).startsWith(ROOT));
        assertTrue(ModulePaths.downloadsDir(ROOT).startsWith(ROOT));
    }

    @Test
    public void tempFileStaysInModuleDirectory() {
        Result<Path> temp = ModulePaths.tempFile(ROOT, "region.module", "config");
        assertTrue(temp.isOk());
        assertTrue(temp.value().toString().endsWith("region.module/config.tmp"));
        assertEquals("PATH_BAD_SUFFIX",
                ModulePaths.tempFile(ROOT, "region.module", "../evil").errorCode());
    }
}
