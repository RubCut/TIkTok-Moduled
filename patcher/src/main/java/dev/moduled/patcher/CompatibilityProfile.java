package dev.moduled.patcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Профиль совместимости (ТЗ §5.1). Одна конфигурация = {package ID, версия,
 * Android (min/target), ABI, split/base}. Патч применяется только к APK,
 * подходящим под профиль; несоответствие показывается до изменений.
 *
 * <p>Профиль сериализуется в {@code profiles.json} и сохраняется вместе с
 * артефактом, чтобы обновления и повторные патчи шли по той же конфигурации.</p>
 */
public final class CompatibilityProfile {

    public static final String SCHEMA_VERSION = "1";

    public final String id;
    public final String packageId;
    public final String versionName;
    public final List<Long> versionCodes;
    public final List<Integer> supportedAbis;
    public final String abiName;
    public final boolean baseApkOnly;
    public final List<String> notes;

    private CompatibilityProfile(Builder builder) {
        this.id = builder.id;
        this.packageId = builder.packageId;
        this.versionName = builder.versionName;
        this.versionCodes = Collections.unmodifiableList(new ArrayList<>(builder.versionCodes));
        this.supportedAbis = Collections.unmodifiableList(new ArrayList<>(builder.supportedAbis));
        this.abiName = builder.abiName;
        this.baseApkOnly = builder.baseApkOnly;
        this.notes = Collections.unmodifiableList(new ArrayList<>(builder.notes));
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** Совместим ли проанализированный APK с профилем. */
    public boolean matches(ApkAnalysis analysis) {
        return mismatchReasons(analysis).isEmpty();
    }

    /** Причины несоответствия: показываются пользователю до изменений (§5.1). */
    public List<String> mismatchReasons(ApkAnalysis analysis) {
        List<String> problems = new ArrayList<>();
        if (!packageId.equals(analysis.packageId)) {
            problems.add("package ID: ожидается " + packageId + ", найден " + analysis.packageId);
        }
        if (!versionCodes.isEmpty() && !versionCodes.contains(analysis.versionCode)) {
            problems.add("versionCode: ожидается один из " + versionCodes
                    + ", найден " + analysis.versionCode);
        }
        if (versionName != null && !versionName.isEmpty()
                && !versionName.equals(analysis.versionName)) {
            problems.add("versionName: ожидается " + versionName + ", найден " + analysis.versionName);
        }
        if (!supportedAbis.isEmpty()) {
            Set<String> wanted = new LinkedHashSet<>();
            for (Integer abi : supportedAbis) {
                wanted.add(AbiSupport.name(abi));
            }
            if (!analysis.abis.contains(abiName)) {
                problems.add("ABI: профиль требует " + abiName + ", в APK найдены " + analysis.abis);
            }
            if (!Collections.disjoint(wanted, analysis.abis) && !analysis.abis.contains(abiName)) {
                problems.add("ABI: выбранный ABI " + abiName + " отсутствует в APK ("
                        + wanted + ')');
            }
        }
        if (baseApkOnly && analysis.looksLikeSplit) {
            problems.add("профиль поддерживает только базовый APK, вход похож на split"
                    + (analysis.splitName != null ? " (" + analysis.splitName + ')' : ""));
        }
        return problems;
    }

    /** Текстовое представление для экрана патчера и отчёта. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Профиль: ").append(id).append(" (schema ").append(SCHEMA_VERSION).append(")\n");
        sb.append("  package: ").append(packageId).append('\n');
        sb.append("  versionName: ").append(versionName).append('\n');
        sb.append("  versionCodes: ").append(versionCodes).append('\n');
        sb.append("  ABI: ").append(abiName).append(" из ").append(supportedAbis).append('\n');
        sb.append("  только базовый APK: ").append(baseApkOnly ? "да" : "нет").append('\n');
        for (String note : notes) {
            sb.append("  примечание: ").append(note).append('\n');
        }
        return sb.toString();
    }

    public static final class Builder {
        private final String id;
        private String packageId = "";
        private String versionName = "";
        private final List<Long> versionCodes = new ArrayList<>();
        private final List<Integer> supportedAbis = new ArrayList<>();
        private String abiName = "arm64-v8a";
        private boolean baseApkOnly = true;
        private final List<String> notes = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder packageId(String value) { this.packageId = value; return this; }
        public Builder versionName(String value) { this.versionName = value; return this; }
        public Builder versionCodes(long... values) {
            this.versionCodes.clear();
            for (long value : values) {
                this.versionCodes.add(value);
            }
            return this;
        }
        public Builder supportedAbis(Integer... values) {
            this.supportedAbis.clear();
            this.supportedAbis.addAll(Arrays.asList(values));
            return this;
        }
        public Builder abiName(String value) { this.abiName = value; return this; }
        public Builder baseApkOnly(boolean value) { this.baseApkOnly = value; return this; }
        public Builder note(String value) { this.notes.add(value); return this; }

        public CompatibilityProfile build() {
            return new CompatibilityProfile(this);
        }
    }
}
