package dev.moduled.runtime;

import android.content.Context;
import android.os.Looper;
import dev.moduled.api.HostInfo;
import dev.moduled.core.CrashPolicy;
import dev.moduled.core.ModulePaths;
import dev.moduled.core.Result;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Точка инициализации runtime (ТЗ §8).
 *
 * <ul>
 *   <li>RUN-01: идемпотентна в пределах процесса.</li>
 *   <li>RUN-02: до исполнения стороннего кода проверяются safe mode, целостность
 *       снимка, формат и совместимость.</li>
 *   <li>RUN-03: при старте нет HTTP-запросов и обновления каталога.</li>
 *   <li>RUN-04: ранняя синхронная работа измеряется и логируется; тяжёлые
 *       операции не выполняются на основном потоке без явной отметки.</li>
 *   <li>RUN-06/RUN-07: детерминированный порядок и изолированная ошибка пакета.</li>
 * </ul>
 */
public final class RuntimeBootstrap {

    private static final Object LOCK = new Object();
    private static volatile RuntimeBootstrap instance;

    private final RuntimeOptions options;
    private final RuntimeLog log;
    private final SafeModeController safeModeController;
    private final CrashReporter crashReporter;
    private final List<ModuleRuntimeState> moduleStates = new ArrayList<>();

    private volatile RuntimeDiagnostics diagnostics;
    private volatile SnapshotData snapshotData;

    private RuntimeBootstrap(RuntimeOptions options) {
        this.options = options;
        File logsDir = ModulePaths.runtimeLogsDir(options.storageRoot.toPath()).toFile();
        this.log = new RuntimeLog(new File(logsDir, "runtime.log"), options.processName);
        File crashDir = ModulePaths.crashReportsDir(options.storageRoot.toPath()).toFile();
        this.crashReporter = new CrashReporter(crashDir, log);
        File stateFile = new File(ModulePaths.snapshotsDir(options.storageRoot.toPath()).toFile(),
                "safe-mode.state");
        this.safeModeController = SafeModeController.create(stateFile, log);
    }

    /** Экземпляр текущего процесса или null, если bootstrap ещё не выполнялся. */
    public static RuntimeBootstrap get() {
        return instance;
    }

    /**
     * Инициализация runtime. Повторный вызов в том же процессе не выполняет
     * загрузку модулей второй раз (RUN-01).
     */
    public static RuntimeBootstrap init(RuntimeOptions options) {
        synchronized (LOCK) {
            if (instance != null) {
                instance.log.log(RuntimeEvent.BOOTSTRAP_SKIPPED, null,
                        "Повторный вызов init() в том же процессе проигнорирован (RUN-01)");
                return instance;
            }
            RuntimeBootstrap bootstrap = new RuntimeBootstrap(options);
            instance = bootstrap;
            bootstrap.start();
            return bootstrap;
        }
    }

    /** Сброс состояния (тесты и аварийное восстановление). */
    static void resetForTest() {
        synchronized (LOCK) {
            instance = null;
        }
    }

    private void start() {
        long startedAt = System.currentTimeMillis();
        boolean onMainThread = Looper.getMainLooper().isCurrentThread();
        log.log(RuntimeEvent.BOOTSTRAP_START, null,
                "runtime " + RuntimeVersion.VERSION + ", процесс " + options.processName
                        + ", основной=" + options.mainProcess
                        + ", поток=" + (onMainThread ? "main" : "background"));

        // §18.2: отчёт сохраняется локально до передачи исходному обработчику.
        crashReporter.install(options.processName, log.sessionId(),
                safeModeController.isSafeModeActive());

        collectExitHistory();

        boolean safeMode = safeModeController.isSafeModeActive();

        SnapshotData data = null;
        Result<SnapshotData> bridgeResult = new BridgeSnapshotSource(
                options.hostContext, options.storageRoot, log,
                options.hostInfo.packageName(), options.processName).load(safeMode);
        if (bridgeResult.isOk()) {
            data = bridgeResult.value();
        } else {
            log.log(RuntimeEvent.SNAPSHOT_UNAVAILABLE, null,
                    "Мост недоступен (" + bridgeResult.errorCode() + ": "
                            + bridgeResult.errorMessage() + "); пробуем локальный снимок");
            Result<SnapshotData> cached =
                    new CachedSnapshotSource(options.storageRoot, log).load(safeMode);
            if (cached.isOk()) {
                data = cached.value();
            } else {
                log.log(RuntimeEvent.SNAPSHOT_UNAVAILABLE, null,
                        "Локальный снимок тоже недоступен: " + cached.errorCode()
                                + " — " + cached.errorMessage());
            }
        }
        this.snapshotData = data;

        if (data != null && !safeMode) {
            ModuleLoader loader = new ModuleLoader(
                    new BootstrapEnvironment(), safeMode, options.hostInfo.packageName());
            moduleStates.addAll(loader.loadAll(data.modules));
        } else if (safeMode) {
            log.log(RuntimeEvent.SAFE_MODE_ACTIVE, null,
                    "Модули не загружаются: safe mode блокирует пользовательские и комплектные пакеты");
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        if (onMainThread && elapsed > 100) {
            // RUN-04: ранняя синхронная работа измеряется; превышение видно в логах.
            log.log(RuntimeEvent.BOOTSTRAP_DONE, null,
                    "ВНИМАНИЕ: bootstrap на основном потоке занял " + elapsed
                            + " ms — требуется перенос или оптимизация");
        }
        log.log(RuntimeEvent.BOOTSTRAP_DONE, null,
                "bootstrap завершён за " + elapsed + " ms; модулей в состояниях: "
                        + moduleStates.size());

        this.diagnostics = buildDiagnostics(elapsed);
    }

    /** CR-06: учет подтверждённых сбоев из истории выходов процесса. */
    private void collectExitHistory() {
        long sinceWallMs = System.currentTimeMillis() - CrashPolicy.WINDOW_MS * 6;
        List<CrashPolicy.Event> events = ApplicationExitInfoCollector.collect(
                options.hostContext, options.processName, sinceWallMs);
        for (CrashPolicy.Event event : events) {
            safeModeController.recordEvent(event);
        }
        log.log(RuntimeEvent.CRASH_REPORTED, null,
                "История выходов: учтено событий " + events.size() + "; "
                        + ApplicationExitInfoCollector.coverageDescription());
    }

    private RuntimeDiagnostics buildDiagnostics(long elapsedMs) {
        SnapshotData data = snapshotData;
        long generation = data != null ? data.snapshot.generation : 0;
        long age = data != null ? data.ageMs(System.currentTimeMillis()) : -1;
        boolean stale = data != null && data.stale;
        String origin = data != null ? data.origin : "none";
        HookBackend backend = options.hookBackend;
        List<String> tail = log.readEntries();
        int from = Math.max(0, tail.size() - 40);
        return RuntimeDiagnostics.builder()
                .processName(options.processName)
                .sessionId(log.sessionId())
                .safeMode(safeModeController.isSafeModeActive())
                .safeModeStateCorrupted(safeModeController.isStateCorrupted())
                .recentCrashCount(safeModeController.recentCrashCount())
                .recentAnrCount(safeModeController.recentAnrCount())
                .snapshotGeneration(generation)
                .snapshotAgeMs(age)
                .snapshotStale(stale)
                .snapshotOrigin(origin)
                .hookBackend(backend.name())
                .hookBackendAvailable(backend.isAvailable())
                .hookBackendReason(backend.unavailabilityReason())
                .adapterName(options.hostInfo.adapterName())
                .adapterSupported(options.hostInfo.adapterSupported())
                .bootstrapElapsedMs(elapsedMs)
                .moduleStates(new ArrayList<>(moduleStates))
                .logTail(new ArrayList<>(tail.subList(from, tail.size())))
                .crashCoverage(ApplicationExitInfoCollector.coverageDescription())
                .build();
    }

    public RuntimeDiagnostics diagnostics() {
        RuntimeDiagnostics current = diagnostics;
        return current != null ? current : buildDiagnostics(0);
    }

    public List<ModuleRuntimeState> moduleStates() {
        return Collections.unmodifiableList(moduleStates);
    }

    public boolean isSafeMode() {
        return safeModeController.isSafeModeActive();
    }

    public SafeModeController safeMode() {
        return safeModeController;
    }

    public RuntimeLog log() {
        return log;
    }

    public CrashReporter crashReporter() {
        return crashReporter;
    }

    public SnapshotData snapshotData() {
        return snapshotData;
    }

    /**
     * Явный выход из safe mode (CR-07). Доступен минимальной части runtime,
     * которая остаётся в safe mode для диагностики и восстановления; модули в
     * этом режиме не загружены и вызвать сброс не могут.
     */
    public void resetSafeMode(String requestedBy) {
        safeModeController.reset(requestedBy);
        diagnostics = buildDiagnostics(0);
    }

    private final class BootstrapEnvironment implements ModuleLoader.Environment {
        @Override
        public Context hostContext() {
            return options.hostContext;
        }

        @Override
        public ClassLoader hostClassLoader() {
            return options.hostContext.getClassLoader();
        }

        @Override
        public String processName() {
            return options.processName;
        }

        @Override
        public boolean isMainProcess() {
            return options.mainProcess;
        }

        @Override
        public File dataDirectoryFor(String moduleId) {
            Result<java.nio.file.Path> dir =
                    ModulePaths.moduleDir(options.storageRoot.toPath(), moduleId);
            if (!dir.isOk()) {
                return null;
            }
            File data = new File(dir.value().toFile(), "data");
            if (!data.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                data.mkdirs();
            }
            return data;
        }

        @Override
        public File optimizedDirectory() {
            File dir = new File(options.storageRoot, "oat");
            if (!dir.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            return dir;
        }

        @Override
        public DexClassLoaderFactory classLoaderFactory() {
            return new AndroidDexClassLoaderFactory(options.hostContext);
        }

        @Override
        public HookBackend hookBackend() {
            return options.hookBackend;
        }

        @Override
        public RuntimeLog log() {
            return log;
        }

        @Override
        public HostInfoProvider hostInfo() {
            return () -> {
                HostInfo info = options.hostInfo;
                return info;
            };
        }

        @Override
        public RestartCoordinator restartCoordinator() {
            return new RestartCoordinator(options.hostContext, new RestartRateLimiter(), log,
                    safeModeController.isSafeModeActive());
        }
    }
}
