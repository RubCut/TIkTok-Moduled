package dev.moduled.patcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * План патча: полный набор изменений до их применения (ТЗ §7.2 PAT-02, PAT-06,
 * PAT-11, §15.2, §22).
 *
 * <p>План показывается пользователю, сохраняется в отчёте и используется как
 * источник детерминированного результата: одинаковый вход и профиль дают
 * одинаковый план.</p>
 */
public final class PatchPlan {

    public final ApkAnalysis analysis;
    public final CompatibilityProfile profile;
    public final EntryPointAnalysis entryPoint;
    public final List<ConflictResolver.Conflict> conflicts;
    public final List<String> addedEntries;
    public final List<String> excludedEntries;
    public final List<String> keptAbis;
    public final boolean repatch;
    public final boolean signatureKeyReused;
    public final String signatureFingerprint;
    public final String targetPackageId;
    public final List<String> warnings;
    public final List<String> blockers;

    private PatchPlan(Builder builder) {
        this.analysis = builder.analysis;
        this.profile = builder.profile;
        this.entryPoint = builder.entryPoint;
        this.conflicts = Collections.unmodifiableList(new ArrayList<>(builder.conflicts));
        this.addedEntries = Collections.unmodifiableList(new ArrayList<>(builder.addedEntries));
        this.excludedEntries = Collections.unmodifiableList(new ArrayList<>(builder.excludedEntries));
        this.keptAbis = Collections.unmodifiableList(new ArrayList<>(builder.keptAbis));
        this.repatch = builder.repatch;
        this.signatureKeyReused = builder.signatureKeyReused;
        this.signatureFingerprint = builder.signatureFingerprint;
        this.targetPackageId = builder.targetPackageId;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(builder.warnings));
        this.blockers = Collections.unmodifiableList(new ArrayList<>(builder.blockers));
    }

    public static Builder builder(ApkAnalysis analysis, CompatibilityProfile profile) {
        return new Builder(analysis, profile);
    }

    public boolean canProceed() {
        return blockers.isEmpty();
    }

    /** Человекочитаемый план для экрана патчера и отчёта (§22). */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== План патча ===\n");
        sb.append(analysis.toReportText());
        if (profile != null) {
            sb.append(profile.toText());
        }
        if (entryPoint != null) {
            sb.append(entryPoint.toText());
        }
        sb.append("Повторный патч: ").append(repatch ? "да (вход уже изменён)" : "нет").append('\n');
        sb.append("Целевой package ID: ").append(targetPackageId).append('\n');
        sb.append("Сохраняемые ABI: ").append(keptAbis.isEmpty() ? "(все)" : keptAbis).append('\n');
        if (!excludedEntries.isEmpty()) {
            sb.append("Исключаемые записи (").append(excludedEntries.size()).append("): ");
            int shown = Math.min(excludedEntries.size(), 8);
            sb.append(excludedEntries.subList(0, shown));
            if (excludedEntries.size() > shown) {
                sb.append(" … ещё ").append(excludedEntries.size() - shown);
            }
            sb.append('\n');
        }
        sb.append("Добавляемые записи:\n");
        for (String entry : addedEntries) {
            sb.append("  + ").append(entry).append('\n');
        }
        if (!conflicts.isEmpty()) {
            sb.append("Конфликты и решения:\n");
            for (ConflictResolver.Conflict conflict : conflicts) {
                sb.append("  ! ").append(conflict).append('\n');
            }
        }
        sb.append("Подпись: ").append(signatureFingerprint == null
                ? "(не определена)" : signatureFingerprint)
                .append(signatureKeyReused ? " — ключ сохранён для обновлений" : "").append('\n');
        for (String warning : warnings) {
            sb.append("ПРЕДУПРЕЖДЕНИЕ: ").append(warning).append('\n');
        }
        for (String blocker : blockers) {
            sb.append("БЛОКЕР: ").append(blocker).append('\n');
        }
        sb.append("Вывод: ").append(canProceed()
                ? "патч возможен" : "патч остановлен до устранения блокеров").append('\n');
        return sb.toString();
    }

    public static final class Builder {
        private final ApkAnalysis analysis;
        private final CompatibilityProfile profile;
        private EntryPointAnalysis entryPoint;
        private final List<ConflictResolver.Conflict> conflicts = new ArrayList<>();
        private final List<String> addedEntries = new ArrayList<>();
        private final List<String> excludedEntries = new ArrayList<>();
        private final List<String> keptAbis = new ArrayList<>();
        private boolean repatch;
        private boolean signatureKeyReused;
        private String signatureFingerprint;
        private String targetPackageId;
        private final List<String> warnings = new ArrayList<>();
        private final List<String> blockers = new ArrayList<>();

        private Builder(ApkAnalysis analysis, CompatibilityProfile profile) {
            this.analysis = analysis;
            this.profile = profile;
            this.targetPackageId = analysis != null ? analysis.packageId : "";
        }

        public Builder entryPoint(EntryPointAnalysis value) { this.entryPoint = value; return this; }
        public Builder addConflict(ConflictResolver.Conflict value) { this.conflicts.add(value); return this; }
        public Builder addConflicts(List<ConflictResolver.Conflict> values) {
            this.conflicts.addAll(values);
            return this;
        }
        public Builder addEntry(String value) { this.addedEntries.add(value); return this; }
        public Builder excludeEntry(String value) { this.excludedEntries.add(value); return this; }
        public Builder addKeptAbi(String value) { this.keptAbis.add(value); return this; }
        public Builder repatch(boolean value) { this.repatch = value; return this; }
        public Builder signatureKeyReused(boolean value) { this.signatureKeyReused = value; return this; }
        public Builder signatureFingerprint(String value) { this.signatureFingerprint = value; return this; }
        public Builder targetPackageId(String value) { this.targetPackageId = value; return this; }
        public Builder warn(String value) { this.warnings.add(value); return this; }
        public Builder block(String value) { this.blockers.add(value); return this; }

        public PatchPlan build() {
            return new PatchPlan(this);
        }
    }
}
