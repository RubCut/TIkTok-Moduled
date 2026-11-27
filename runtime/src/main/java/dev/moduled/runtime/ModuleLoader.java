package dev.moduled.runtime;

import android.content.Context;
import dev.moduled.api.ModuleContext;
import dev.moduled.api.TModule;
import dev.moduled.core.ModuleManifest;
import dev.moduled.core.Result;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Загрузка и запуск модулей (ТЗ §8 RUN-02, RUN-05..RUN-08).
 *
 * <p>Порядок детерминирован: стабильная сортировка по ID. Ошибка проверки одного
 * пакета блокирует только его исполнение и отражается в диагностике; остальные
 * модули продолжают загрузку, если целостность runtime не нарушена.</p>
 */
public final class ModuleLoader {

    /** Окружение загрузки: всё, что loader не должен создавать сам. */
    public interface Environment {
        Context hostContext();

        ClassLoader hostClassLoader();

        String processName();

        boolean isMainProcess();

        File dataDirectoryFor(String moduleId);

        File optimizedDirectory();

        DexClassLoaderFactory classLoaderFactory();

        HookBackend hookBackend();

        RuntimeLog log();

        HostInfoProvider hostInfo();

        RestartCoordinator restartCoordinator();
    }

    /** Поставщик сведений о хосте (адаптер выбирается патчером). */
    public interface HostInfoProvider {
        dev.moduled.api.HostInfo hostInfo();
    }

    private final Environment environment;
    private final boolean safeMode;
    private final String hostPackage;

    public ModuleLoader(Environment environment, boolean safeMode, String hostPackage) {
        this.environment = environment;
        this.safeMode = safeMode;
        this.hostPackage = hostPackage;
    }

    /** Загружает модули в детерминированном порядке и возвращает состояния. */
    public List<ModuleRuntimeState> loadAll(List<ResolvedModule> modules) {
        List<ResolvedModule> ordered = new ArrayList<>(modules);
        // RUN-06: стабильная сортировка по ID.
        ordered.sort(Comparator.comparing(m -> m.manifest.id));

        List<ModuleRuntimeState> states = new ArrayList<>(ordered.size());
        for (ResolvedModule module : ordered) {
            states.add(loadOne(module));
        }
        return states;
    }

    private ModuleRuntimeState loadOne(ResolvedModule module) {
        String id = module.manifest.id;
        RuntimeLog log = environment.log();

        // CR-03: safe mode блокирует все пользовательские и комплектные модули.
        if (safeMode) {
            log.log(RuntimeEvent.MODULE_BLOCKED_SAFE_MODE, id,
                    "Модуль не загружается: активен safe mode");
            return ModuleRuntimeState.of(id, ModuleRuntimeState.State.BLOCKED_SAFE_MODE,
                    "Активен safe mode");
        }

        log.log(RuntimeEvent.MODULE_SELECTED, id,
                "Выбран из снимка поколения " + module.generation);

        // §9.2: несовместимая версия API блокируется до вызова entrypoint.
        Result<Void> compatibility = checkCompatibility(module.manifest);
        if (!compatibility.isOk()) {
            log.log(RuntimeEvent.MODULE_INCOMPATIBLE, id, compatibility.errorMessage());
            return ModuleRuntimeState.of(id, ModuleRuntimeState.State.INCOMPATIBLE,
                    compatibility.errorMessage());
        }

        File dexFile = module.dexFile;
        if (dexFile == null || !dexFile.isFile()) {
            String message = "DEX отсутствует в private storage: " + dexFile;
            log.log(RuntimeEvent.MODULE_ERROR, id, message);
            return ModuleRuntimeState.of(id, ModuleRuntimeState.State.ERROR, message);
        }
        log.log(RuntimeEvent.MODULE_COPIED_TO_SNAPSHOT, id,
                "DEX подготовлен: " + dexFile.getName() + " sha256=" + module.expectedDexSha256);

        ClassLoader moduleClassLoader;
        try {
            moduleClassLoader = environment.classLoaderFactory().create(
                    dexFile, environment.optimizedDirectory(), environment.hostClassLoader());
        } catch (Throwable t) {
            String message = "Не удалось создать ClassLoader: " + t;
            log.log(RuntimeEvent.MODULE_ERROR, id, message, t);
            return ModuleRuntimeState.of(id, ModuleRuntimeState.State.ERROR, message);
        }

        Class<?> entryClass;
        try {
            entryClass = Class.forName(module.manifest.entrypoint, true, moduleClassLoader);
        } catch (Throwable t) {
            String message = "Класс точки входа не найден: " + module.manifest.entrypoint + " — " + t;
            log.log(RuntimeEvent.MODULE_ERROR, id, message, t);
            return ModuleRuntimeState.of(id, ModuleRuntimeState.State.ERROR, message);
        }
        if (!TModule.class.isAssignableFrom(entryClass)) {
            String message = "Класс " + entryClass.getName() + " не реализует dev.moduled.api.TModule";
            log.log(RuntimeEvent.MODULE_ERROR, id, message);
            return ModuleRuntimeState.of(id, ModuleRuntimeState.State.ERROR, message);
        }
        log.log(RuntimeEvent.MODULE_LOADED, id, "Класс точки входа загружен: " + entryClass.getName());

        TModule instance;
        try {
            instance = (TModule) entryClass.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            String message = "Не удалось создать экземпляр модуля: " + t;
            log.log(RuntimeEvent.MODULE_ERROR, id, message, t);
            return ModuleRuntimeState.of(id, ModuleRuntimeState.State.ERROR, message);
        }

        BackendHookApi hookApi = new BackendHookApi(
                environment.hookBackend(), log, id);
        ModuleContext context = new RuntimeModuleContext(
                environment.hostContext(),
                environment.hostClassLoader(),
                moduleClassLoader,
                module.manifest,
                environment.processName(),
                environment.isMainProcess(),
                environment.dataDirectoryFor(id),
                new RuntimeConfigSnapshot(module.configBytes, module.configSha256, module.generation),
                new RuntimeLogger(log, id),
                hookApi,
                environment.hostInfo().hostInfo(),
                environment.restartCoordinator());

        int hooksBefore = countHooks(hookApi);
        try {
            instance.onLoad(context);
            log.log(RuntimeEvent.MODULE_STARTED, id,
                    "onLoad() завершён; backend=" + hookApi.backendName());
            return new ModuleRuntimeState(id, ModuleRuntimeState.State.STARTED,
                    "Модуль запущен", false, countHooks(hookApi) - hooksBefore);
        } catch (Throwable t) {
            // RUN-08: исключение после установки хуков не является чистым откатом.
            int installed = countHooks(hookApi) - hooksBefore;
            boolean needsRestart = installed > 0;
            String message = "onLoad() завершился ошибкой: " + t
                    + (needsRestart
                    ? "; установлено хуков: " + installed + " — требуется перезапуск процесса"
                    : "");
            log.log(RuntimeEvent.MODULE_ERROR, id, message, t);
            return new ModuleRuntimeState(id, ModuleRuntimeState.State.ERROR, message,
                    needsRestart, Math.max(installed, 0));
        }
    }

    private Result<Void> checkCompatibility(ModuleManifest manifest) {
        if (manifest.apiVersion != dev.moduled.api.ModuleContext.API_VERSION) {
            return Result.fail("MODULE_API_MISMATCH",
                    "Модуль собран для apiVersion " + manifest.apiVersion
                            + ", runtime поддерживает " + dev.moduled.api.ModuleContext.API_VERSION);
        }
        if (hostPackage != null && !manifest.targetsPackage(hostPackage)) {
            return Result.fail("MODULE_TARGET_MISMATCH",
                    "Модуль не рассчитан на пакет " + hostPackage
                            + " (targetPackages=" + manifest.targetPackages + ')');
        }
        return Result.ok(null);
    }

    private static int countHooks(BackendHookApi hookApi) {
        return hookApi.installedCount();
    }
}
