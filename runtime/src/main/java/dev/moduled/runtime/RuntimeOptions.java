package dev.moduled.runtime;

import android.content.Context;
import java.io.File;

/**
 * Параметры запуска runtime. Всё, что зависит от хоста и от патчера, передаётся
 * явно: runtime не угадывает точку входа и не включает UI-библиотеки (ТЗ §21.1).
 */
public final class RuntimeOptions {

    public final Context hostContext;
    public final File storageRoot;
    public final String processName;
    public final boolean mainProcess;
    public final HookBackend hookBackend;
    public final dev.moduled.api.HostInfo hostInfo;

    private RuntimeOptions(Builder builder) {
        this.hostContext = builder.hostContext;
        this.storageRoot = builder.storageRoot;
        this.processName = builder.processName;
        this.mainProcess = builder.mainProcess;
        this.hookBackend = builder.hookBackend;
        this.hostInfo = builder.hostInfo;
    }

    public static Builder builder(Context hostContext) {
        return new Builder(hostContext);
    }

    public static final class Builder {
        private final Context hostContext;
        private File storageRoot;
        private String processName;
        private boolean mainProcess = true;
        private HookBackend hookBackend = new UnavailableHookBackend();
        private dev.moduled.api.HostInfo hostInfo;

        private Builder(Context hostContext) {
            if (hostContext == null) {
                throw new IllegalArgumentException("hostContext must not be null");
            }
            this.hostContext = hostContext.getApplicationContext() != null
                    ? hostContext.getApplicationContext() : hostContext;
        }

        public Builder storageRoot(File storageRoot) {
            this.storageRoot = storageRoot;
            return this;
        }

        public Builder processName(String processName) {
            this.processName = processName;
            return this;
        }

        public Builder mainProcess(boolean mainProcess) {
            this.mainProcess = mainProcess;
            return this;
        }

        public Builder hookBackend(HookBackend backend) {
            this.hookBackend = backend != null ? backend : new UnavailableHookBackend();
            return this;
        }

        public Builder hostInfo(dev.moduled.api.HostInfo hostInfo) {
            this.hostInfo = hostInfo;
            return this;
        }

        public RuntimeOptions build() {
            if (storageRoot == null) {
                storageRoot = new File(hostContext.getFilesDir(), "tmoduled");
            }
            if (processName == null) {
                processName = hostContext.getPackageName();
            }
            if (hostInfo == null) {
                hostInfo = AdapterMetadata.load(hostContext);
            }
            return new RuntimeOptions(this);
        }
    }
}
