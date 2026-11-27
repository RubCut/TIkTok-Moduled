package dev.moduled.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Диагностика запуска для менеджера, внутреннего менеджера и пользователя
 * (ТЗ §5.2 п.8, §21.2 экран 8). Никаких догадок: только фактические состояния.
 */
public final class RuntimeDiagnostics {

    public final String runtimeVersion;
    public final int apiVersion;
    public final String processName;
    public final String sessionId;
    public final boolean safeMode;
    public final boolean safeModeStateCorrupted;
    public final int recentCrashCount;
    public final int recentAnrCount;
    public final long snapshotGeneration;
    public final long snapshotAgeMs;
    public final boolean snapshotStale;
    public final String snapshotOrigin;
    public final String hookBackend;
    public final boolean hookBackendAvailable;
    public final String hookBackendReason;
    public final String adapterName;
    public final boolean adapterSupported;
    public final long bootstrapElapsedMs;
    public final List<ModuleRuntimeState> moduleStates;
    public final List<String> logTail;
    public final String crashCoverage;

    private RuntimeDiagnostics(Builder b) {
        this.runtimeVersion = b.runtimeVersion;
        this.apiVersion = b.apiVersion;
        this.processName = b.processName;
        this.sessionId = b.sessionId;
        this.safeMode = b.safeMode;
        this.safeModeStateCorrupted = b.safeModeStateCorrupted;
        this.recentCrashCount = b.recentCrashCount;
        this.recentAnrCount = b.recentAnrCount;
        this.snapshotGeneration = b.snapshotGeneration;
        this.snapshotAgeMs = b.snapshotAgeMs;
        this.snapshotStale = b.snapshotStale;
        this.snapshotOrigin = b.snapshotOrigin;
        this.hookBackend = b.hookBackend;
        this.hookBackendAvailable = b.hookBackendAvailable;
        this.hookBackendReason = b.hookBackendReason;
        this.adapterName = b.adapterName;
        this.adapterSupported = b.adapterSupported;
        this.bootstrapElapsedMs = b.bootstrapElapsedMs;
        this.moduleStates = Collections.unmodifiableList(new ArrayList<>(b.moduleStates));
        this.logTail = Collections.unmodifiableList(new ArrayList<>(b.logTail));
        this.crashCoverage = b.crashCoverage;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Человекочитаемая сводка для экрана диагностики и копирования. */
    public String toReport() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("TikTok Moduled runtime ").append(runtimeVersion)
                .append(" (apiVersion ").append(apiVersion).append(")\n");
        sb.append("process=").append(processName).append(" session=").append(sessionId).append('\n');
        sb.append("safeMode=").append(safeMode);
        if (safeModeStateCorrupted) {
            sb.append(" (состояние защиты повреждено — консервативный режим)");
        }
        sb.append(" crashes=").append(recentCrashCount)
                .append(" anr=").append(recentAnrCount).append('\n');
        sb.append("snapshot generation=").append(snapshotGeneration)
                .append(" age=").append(snapshotAgeMs).append(" ms")
                .append(snapshotStale ? " [УСТАРЕЛ]" : "")
                .append(" origin=").append(snapshotOrigin).append('\n');
        sb.append("hookBackend=").append(hookBackend)
                .append(hookBackendAvailable ? " (доступен)" : " (недоступен: " + hookBackendReason + ')')
                .append('\n');
        sb.append("adapter=").append(adapterName)
                .append(adapterSupported ? " (поддерживается)" : " (поддержка не подтверждена)")
                .append('\n');
        sb.append("bootstrap=").append(bootstrapElapsedMs).append(" ms\n");
        sb.append("crashCoverage=").append(crashCoverage).append('\n');
        sb.append("--- modules ---\n");
        if (moduleStates.isEmpty()) {
            sb.append("(нет активных модулей)\n");
        }
        for (ModuleRuntimeState state : moduleStates) {
            sb.append(state.moduleId).append(": ").append(state.state)
                    .append(" hooks=").append(state.hooksInstalled);
            if (state.needsRestart) {
                sb.append(" [требуется перезапуск]");
            }
            if (state.message != null) {
                sb.append(" — ").append(state.message);
            }
            sb.append('\n');
        }
        sb.append("--- log tail ---\n");
        for (String line : logTail) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static final class Builder {
        private String runtimeVersion = RuntimeVersion.VERSION;
        private int apiVersion = RuntimeVersion.API_VERSION;
        private String processName = "-";
        private String sessionId = "-";
        private boolean safeMode;
        private boolean safeModeStateCorrupted;
        private int recentCrashCount;
        private int recentAnrCount;
        private long snapshotGeneration;
        private long snapshotAgeMs;
        private boolean snapshotStale;
        private String snapshotOrigin = "none";
        private String hookBackend = UnavailableHookBackend.NAME;
        private boolean hookBackendAvailable;
        private String hookBackendReason = "";
        private String adapterName = "unknown";
        private boolean adapterSupported;
        private long bootstrapElapsedMs;
        private List<ModuleRuntimeState> moduleStates = new ArrayList<>();
        private List<String> logTail = new ArrayList<>();
        private String crashCoverage = "";

        public Builder runtimeVersion(String value) { this.runtimeVersion = value; return this; }
        public Builder apiVersion(int value) { this.apiVersion = value; return this; }
        public Builder processName(String value) { this.processName = value; return this; }
        public Builder sessionId(String value) { this.sessionId = value; return this; }
        public Builder safeMode(boolean value) { this.safeMode = value; return this; }
        public Builder safeModeStateCorrupted(boolean value) { this.safeModeStateCorrupted = value; return this; }
        public Builder recentCrashCount(int value) { this.recentCrashCount = value; return this; }
        public Builder recentAnrCount(int value) { this.recentAnrCount = value; return this; }
        public Builder snapshotGeneration(long value) { this.snapshotGeneration = value; return this; }
        public Builder snapshotAgeMs(long value) { this.snapshotAgeMs = value; return this; }
        public Builder snapshotStale(boolean value) { this.snapshotStale = value; return this; }
        public Builder snapshotOrigin(String value) { this.snapshotOrigin = value; return this; }
        public Builder hookBackend(String value) { this.hookBackend = value; return this; }
        public Builder hookBackendAvailable(boolean value) { this.hookBackendAvailable = value; return this; }
        public Builder hookBackendReason(String value) { this.hookBackendReason = value; return this; }
        public Builder adapterName(String value) { this.adapterName = value; return this; }
        public Builder adapterSupported(boolean value) { this.adapterSupported = value; return this; }
        public Builder bootstrapElapsedMs(long value) { this.bootstrapElapsedMs = value; return this; }
        public Builder moduleStates(List<ModuleRuntimeState> value) { this.moduleStates = value; return this; }
        public Builder logTail(List<String> value) { this.logTail = value; return this; }
        public Builder crashCoverage(String value) { this.crashCoverage = value; return this; }

        public RuntimeDiagnostics build() {
            return new RuntimeDiagnostics(this);
        }
    }
}
