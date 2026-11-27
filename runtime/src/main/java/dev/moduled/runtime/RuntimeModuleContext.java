package dev.moduled.runtime;

import android.content.Context;
import dev.moduled.api.ConfigSnapshot;
import dev.moduled.api.HookApi;
import dev.moduled.api.HostInfo;
import dev.moduled.api.ModuleContext;
import dev.moduled.api.ModuleLogger;
import dev.moduled.api.RestartResult;
import dev.moduled.core.ModuleManifest;
import java.io.File;

/** Реализация {@link ModuleContext}, которую runtime передаёт модулю (ТЗ §9). */
public final class RuntimeModuleContext implements ModuleContext {

    private final Context hostContext;
    private final ClassLoader hostClassLoader;
    private final ClassLoader moduleClassLoader;
    private final ModuleManifest manifest;
    private final String processName;
    private final boolean mainProcess;
    private final File dataDirectory;
    private final ConfigSnapshot config;
    private final ModuleLogger logger;
    private final HookApi hooks;
    private final HostInfo host;
    private final RestartCoordinator restartCoordinator;

    public RuntimeModuleContext(Context hostContext, ClassLoader hostClassLoader,
                                ClassLoader moduleClassLoader, ModuleManifest manifest,
                                String processName, boolean mainProcess, File dataDirectory,
                                ConfigSnapshot config, ModuleLogger logger, HookApi hooks,
                                HostInfo host, RestartCoordinator restartCoordinator) {
        this.hostContext = hostContext;
        this.hostClassLoader = hostClassLoader;
        this.moduleClassLoader = moduleClassLoader;
        this.manifest = manifest;
        this.processName = processName;
        this.mainProcess = mainProcess;
        this.dataDirectory = dataDirectory;
        this.config = config;
        this.logger = logger;
        this.hooks = hooks;
        this.host = host;
        this.restartCoordinator = restartCoordinator;
        if (dataDirectory != null && !dataDirectory.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dataDirectory.mkdirs();
        }
    }

    @Override
    public Context hostContext() {
        return hostContext;
    }

    @Override
    public ClassLoader hostClassLoader() {
        return hostClassLoader;
    }

    @Override
    public ClassLoader moduleClassLoader() {
        return moduleClassLoader;
    }

    @Override
    public String moduleId() {
        return manifest.id;
    }

    @Override
    public String moduleVersion() {
        return manifest.version;
    }

    @Override
    public long moduleVersionCode() {
        return manifest.versionCode;
    }

    @Override
    public int apiVersion() {
        return manifest.apiVersion;
    }

    @Override
    public String runtimeVersion() {
        return RuntimeVersion.VERSION;
    }

    @Override
    public String processName() {
        return processName;
    }

    @Override
    public boolean isMainProcess() {
        return mainProcess;
    }

    @Override
    public File dataDirectory() {
        return dataDirectory;
    }

    @Override
    public ConfigSnapshot config() {
        return config;
    }

    @Override
    public ModuleLogger log() {
        return logger;
    }

    @Override
    public HookApi hooks() {
        return hooks;
    }

    @Override
    public HostInfo host() {
        return host;
    }

    @Override
    public RestartResult requestRestart(String reason) {
        return restartCoordinator.requestRestart(manifest.id, reason);
    }
}
