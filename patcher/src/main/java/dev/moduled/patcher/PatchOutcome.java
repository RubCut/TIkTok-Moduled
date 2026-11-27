package dev.moduled.patcher;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Результат патча (ТЗ §7.1, §7.5, §22). */
public final class PatchOutcome {

    public final File output;
    public final String sha256;
    public final long sizeBytes;
    public final String packageId;
    public final String signatureFingerprint;
    public final boolean repatch;
    public final List<String> planText;
    public final List<String> manifestDiff;
    public final List<String> excludedAbis;
    public final List<String> warnings;
    public final List<String> blockers;

    private PatchOutcome(Builder builder) {
        this.output = builder.output;
        this.sha256 = builder.sha256;
        this.sizeBytes = builder.sizeBytes;
        this.packageId = builder.packageId;
        this.signatureFingerprint = builder.signatureFingerprint;
        this.repatch = builder.repatch;
        this.planText = Collections.unmodifiableList(new ArrayList<>(builder.planText));
        this.manifestDiff = Collections.unmodifiableList(new ArrayList<>(builder.manifestDiff));
        this.excludedAbis = Collections.unmodifiableList(new ArrayList<>(builder.excludedAbis));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(builder.warnings));
        this.blockers = Collections.unmodifiableList(new ArrayList<>(builder.blockers));
    }

    public boolean succeeded() {
        return blockers.isEmpty() && output != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Полный отчёт для экрана и файла (§22, §26.5: без секретов). */
    public String toReportText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Результат патча ===\n");
        if (output != null) {
            sb.append("Файл: ").append(output.getAbsolutePath()).append('\n');
            sb.append("Размер: ").append(sizeBytes).append(" байт\n");
            sb.append("SHA-256: ").append(sha256).append('\n');
        }
        sb.append("package ID: ").append(packageId).append('\n');
        sb.append("Подпись: ").append(signatureFingerprint).append('\n');
        sb.append("Повторный патч: ").append(repatch ? "да" : "нет").append('\n');
        if (!excludedAbis.isEmpty()) {
            sb.append("Исключённые ABI: ").append(excludedAbis).append('\n');
        }
        for (String line : planText) {
            sb.append(line);
        }
        for (String line : manifestDiff) {
            sb.append(line).append('\n');
        }
        for (String warning : warnings) {
            sb.append("ПРЕДУПРЕЖДЕНИЕ: ").append(warning).append('\n');
        }
        for (String blocker : blockers) {
            sb.append("БЛОКЕР: ").append(blocker).append('\n');
        }
        return sb.toString();
    }

    public static final class Builder {
        private File output;
        private String sha256 = "";
        private long sizeBytes;
        private String packageId = "";
        private String signatureFingerprint = "";
        private boolean repatch;
        private final List<String> planText = new ArrayList<>();
        private final List<String> manifestDiff = new ArrayList<>();
        private final List<String> excludedAbis = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> blockers = new ArrayList<>();

        public Builder output(File value) { this.output = value; return this; }
        public Builder sha256(String value) { this.sha256 = value; return this; }
        public Builder sizeBytes(long value) { this.sizeBytes = value; return this; }
        public Builder packageId(String value) { this.packageId = value; return this; }
        public Builder signatureFingerprint(String value) { this.signatureFingerprint = value; return this; }
        public Builder repatch(boolean value) { this.repatch = value; return this; }
        public Builder addPlan(String value) { this.planText.add(value); return this; }
        public Builder addManifestDiff(String value) { this.manifestDiff.add(value); return this; }
        public Builder addExcludedAbi(String value) { this.excludedAbis.add(value); return this; }
        public Builder warn(String value) { this.warnings.add(value); return this; }
        public Builder block(String value) { this.blockers.add(value); return this; }

        public PatchOutcome build() {
            return new PatchOutcome(this);
        }
    }
}
