package dev.moduled.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import dev.moduled.api.HostInfo;
import dev.moduled.core.ModuleManifest;
import dev.moduled.runtime.fixture.FakeModule;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Загрузка модулей (ТЗ §8): детерминированный порядок, блокировка safe mode,
 * отказ по совместимости, изолированная ошибка пакета и признак RUN-08.
 */
public class ModuleLoaderTest {

    private static final String HOST_PACKAGE = "dev.moduled.demo";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private RuntimeLog log;
    private InterceptionRegistry registry = new InterceptionRegistry();

    private RuntimeLog log() throws IOException {
        if (log == null) {
            log = new RuntimeLog(new File(tmp.newFolder("logs"), "runtime.log"), "test-process");
        }
        return log;
    }

    private ModuleManifest manifest(String id, int apiVersion, String entrypoint,
                                    List<String> targets) {
        return new ModuleManifest(id, "Name " + id, "desc", "1.0.0", 1, apiVersion,
                entrypoint, targets);
    }

    private ResolvedModule module(String id, int apiVersion, String entrypoint,
                                  String configText) throws IOException {
        File dex = new File(tmp.newFolder("dex-" + id.replace('.', '_')), "classes.dex");
        // Loader проверяет наличие файла и хеш на этапе импорта (DexImporter),
        // поэтому здесь достаточно валидного DEX-заголовка.
        Files.write(dex.toPath(), TestDex.validHeader());
        return new ResolvedModule(
                manifest(id, apiVersion, entrypoint, List.of(HOST_PACKAGE)),
                dex, "hash-" + id, configText.getBytes(StandardCharsets.UTF_8), "cfg-hash", 5L);
    }

    private ModuleLoader loader(boolean safeMode) throws IOException {
        return new ModuleLoader(new FakeEnvironment(), safeMode, HOST_PACKAGE);
    }

    /** CR-03: safe mode блокирует все модули, включая комплектные. */
    @Test
    public void safeModeBlocksEveryModule() throws IOException {
        FakeModule.reset();
        List<ModuleRuntimeState> states = loader(true).loadAll(List.of(
                module("region.module", 1, FakeModule.class.getName(), ""),
                module("sample.module", 1, FakeModule.class.getName(), "")));
        assertEquals(2, states.size());
        for (ModuleRuntimeState state : states) {
            assertEquals(ModuleRuntimeState.State.BLOCKED_SAFE_MODE, state.state);
            assertFalse(state.isRunnable());
        }
        assertNull("onLoad must not run in safe mode", FakeModule.lastModuleId);
    }

    /** §9.2: несовместимая версия API блокируется до вызова entrypoint. */
    @Test
    public void unsupportedApiVersionIsRejectedBeforeEntrypoint() throws IOException {
        FakeModule.reset();
        List<ModuleRuntimeState> states = loader(false).loadAll(List.of(
                module("future.module", 2, FakeModule.class.getName(), "")));
        assertEquals(ModuleRuntimeState.State.INCOMPATIBLE, states.get(0).state);
        assertTrue(states.get(0).message.contains("apiVersion 2"));
        assertNull(FakeModule.lastModuleId);
    }

    @Test
    public void foreignTargetPackageIsRejected() throws IOException {
        ResolvedModule module = new ResolvedModule(
                manifest("other.module", 1, FakeModule.class.getName(),
                        List.of("com.some.other.app")),
                new File(tmp.getRoot(), "absent.dex"), "h", new byte[0], "c", 1L);
        List<ModuleRuntimeState> states = loader(false).loadAll(List.of(module));
        assertEquals(ModuleRuntimeState.State.INCOMPATIBLE, states.get(0).state);
        assertTrue(states.get(0).message.contains("com.some.other.app"));
    }

    @Test
    public void successfulModuleStartsAndInstallsHook() throws IOException {
        FakeModule.reset();
        registry = new InterceptionRegistry();
        List<ModuleRuntimeState> states = loader(false).loadAll(List.of(
                module("sample.module", 1, FakeModule.class.getName(), "key = value")));
        ModuleRuntimeState state = states.get(0);
        assertEquals(state.message, ModuleRuntimeState.State.STARTED, state.state);
        assertTrue(state.isRunnable());
        assertEquals("sample.module", FakeModule.lastModuleId);
        assertEquals("key = value", FakeModule.lastConfigText);
        assertEquals(1, state.hooksInstalled);
        assertTrue(FakeModule.hookCount == 1);
    }

    /** RUN-07: ошибка одного пакета не блокирует остальные. */
    @Test
    public void errorInOneModuleDoesNotStopOthers() throws IOException {
        FakeModule.reset();
        List<ModuleRuntimeState> states = loader(false).loadAll(List.of(
                module("a.failing", 1,
                        "dev.moduled.runtime.fixture.FailingModule", ""),
                module("b.working", 1, FakeModule.class.getName(), "")));
        assertEquals(ModuleRuntimeState.State.ERROR, states.get(0).state);
        assertEquals(ModuleRuntimeState.State.STARTED, states.get(1).state);
        assertEquals("b.working", FakeModule.lastModuleId);
    }

    /** RUN-08: исключение после установки хуков требует перезапуска. */
    @Test
    public void failureAfterHookRequiresRestart() throws IOException {
        List<ModuleRuntimeState> states = loader(false).loadAll(List.of(
                module("c.late.failure", 1,
                        "dev.moduled.runtime.fixture.FailingAfterHookModule", "")));
        ModuleRuntimeState state = states.get(0);
        assertEquals(ModuleRuntimeState.State.ERROR, state.state);
        assertTrue("needsRestart must be set when hooks were installed", state.needsRestart);
        assertTrue(state.message.contains("требуется перезапуск"));
        assertEquals(1, state.hooksInstalled);
    }

    @Test
    public void failureBeforeHookDoesNotRequireRestart() throws IOException {
        List<ModuleRuntimeState> states = loader(false).loadAll(List.of(
                module("d.early.failure", 1, "dev.moduled.runtime.fixture.FailingModule", "")));
        ModuleRuntimeState state = states.get(0);
        assertEquals(ModuleRuntimeState.State.ERROR, state.state);
        assertFalse(state.needsRestart);
        assertTrue(state.message.contains("module exploded"));
    }

    @Test
    public void entrypointMustImplementTModule() throws IOException {
        List<ModuleRuntimeState> states = loader(false).loadAll(List.of(
                module("e.notamodule", 1, "dev.moduled.runtime.fixture.NotAModule", "")));
        assertEquals(ModuleRuntimeState.State.ERROR, states.get(0).state);
        assertTrue(states.get(0).message.contains("не реализует"));
    }

    @Test
    public void missingEntrypointClassIsReported() throws IOException {
        List<ModuleRuntimeState> states = loader(false).loadAll(List.of(
                module("f.absent", 1, "dev.moduled.runtime.fixture.DoesNotExist", "")));
        assertEquals(ModuleRuntimeState.State.ERROR, states.get(0).state);
        assertTrue(states.get(0).message.contains("не найден"));
    }

    @Test
    public void missingDexFileIsReported() throws IOException {
        ResolvedModule module = new ResolvedModule(
                manifest("g.nodex", 1, FakeModule.class.getName(), List.of(HOST_PACKAGE)),
                new File(tmp.getRoot(), "missing/classes.dex"), "h", new byte[0], "c", 1L);
        List<ModuleRuntimeState> states = loader(false).loadAll(List.of(module));
        assertEquals(ModuleRuntimeState.State.ERROR, states.get(0).state);
        assertTrue(states.get(0).message.contains("DEX отсутствует"));
    }

    /** RUN-06: стабильная сортировка по ID независимо от порядка в снимке. */
    @Test
    public void loadOrderIsDeterministicById() throws IOException {
        FakeModule.reset();
        List<ResolvedModule> shuffled = new ArrayList<>(List.of(
                module("zeta.module", 1, FakeModule.class.getName(), ""),
                module("alpha.module", 1, FakeModule.class.getName(), ""),
                module("mid.module", 1, FakeModule.class.getName(), "")));
        List<ModuleRuntimeState> states = loader(false).loadAll(shuffled);
        List<String> ids = new ArrayList<>();
        for (ModuleRuntimeState state : states) {
            ids.add(state.moduleId);
        }
        assertEquals(List.of("alpha.module", "mid.module", "zeta.module"), ids);
    }

    private final class FakeEnvironment implements ModuleLoader.Environment {
        @Override
        public Context hostContext() {
            return null;
        }

        @Override
        public ClassLoader hostClassLoader() {
            return ModuleLoaderTest.class.getClassLoader();
        }

        @Override
        public String processName() {
            return "dev.moduled.demo";
        }

        @Override
        public boolean isMainProcess() {
            return true;
        }

        @Override
        public File dataDirectoryFor(String moduleId) {
            return new File(tmp.getRoot(), "data/" + moduleId);
        }

        @Override
        public File optimizedDirectory() {
            return new File(tmp.getRoot(), "oat");
        }

        @Override
        public DexClassLoaderFactory classLoaderFactory() {
            return (dexFile, optimizedDirectory, parent) -> ModuleLoaderTest.class.getClassLoader();
        }

        @Override
        public HookBackend hookBackend() {
            return new RegistryHookBackend(registry);
        }

        @Override
        public RuntimeLog log() {
            try {
                return ModuleLoaderTest.this.log();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ModuleLoader.HostInfoProvider hostInfo() {
            return () -> new RuntimeHostInfo(HOST_PACKAGE, "1.0.0", 100,
                    "demo-adapter", "1", true, new String[] {"arm64-v8a"});
        }

        @Override
        public RestartCoordinator restartCoordinator() {
            return new RestartCoordinator(null, new RestartRateLimiter(), log(), false);
        }
    }
}
