package dev.moduled.patcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Результат предварительного анализа APK (ТЗ §7.2 PAT-01): manifest, package ID,
 * версии, SDK-ограничения, ABI, структура DEX и native-файлов, подпись, маркеры
 * предыдущего патча.
 */
public final class ApkAnalysis {

    public final java.io.File file;
    public final String packageId;
    public final String versionName;
    public final long versionCode;
    public final Integer minSdkVersion;
    public final Integer targetSdkVersion;
    public final Set<String> abis;
    public final List<String> dexEntries;
    public final long dexTotalBytes;
    public final List<String> nativeLibraries;
    public final List<String> usesPermissions;
    public final List<String> declaredPermissions;
    public final List<String> providerAuthorities;
    public final List<String> activityNames;
    public final String applicationName;
    public final List<String> signatureFiles;
    public final boolean hasResourcesArsc;
    public final List<String> markerEntries;
    public final String sha256;
    public final long sizeBytes;
    public final String splitName;
    public final boolean looksLikeSplit;
    public final List<String> launcherActivities;

    private ApkAnalysis(Builder builder) {
        this.file = builder.file;
        this.packageId = builder.packageId;
        this.versionName = builder.versionName;
        this.versionCode = builder.versionCode;
        this.minSdkVersion = builder.minSdkVersion;
        this.targetSdkVersion = builder.targetSdkVersion;
        this.abis = Collections.unmodifiableSet(new LinkedHashSet<>(builder.abis));
        this.dexEntries = Collections.unmodifiableList(new ArrayList<>(builder.dexEntries));
        this.dexTotalBytes = builder.dexTotalBytes;
        this.nativeLibraries = Collections.unmodifiableList(new ArrayList<>(builder.nativeLibraries));
        this.usesPermissions = Collections.unmodifiableList(new ArrayList<>(builder.usesPermissions));
        this.declaredPermissions = Collections.unmodifiableList(new ArrayList<>(builder.declaredPermissions));
        this.providerAuthorities = Collections.unmodifiableList(new ArrayList<>(builder.providerAuthorities));
        this.activityNames = Collections.unmodifiableList(new ArrayList<>(builder.activityNames));
        this.applicationName = builder.applicationName;
        this.signatureFiles = Collections.unmodifiableList(new ArrayList<>(builder.signatureFiles));
        this.hasResourcesArsc = builder.hasResourcesArsc;
        this.markerEntries = Collections.unmodifiableList(new ArrayList<>(builder.markerEntries));
        this.sha256 = builder.sha256;
        this.sizeBytes = builder.sizeBytes;
        this.splitName = builder.splitName;
        this.looksLikeSplit = builder.looksLikeSplit;
        this.launcherActivities = Collections.unmodifiableList(
                new ArrayList<>(builder.launcherActivities));
    }

    public static Builder builder(java.io.File file) {
        return new Builder(file);
    }

    /** Уже изменённый вход: есть маркеры платформы (PAT-11). */
    public boolean alreadyPatched() {
        return !markerEntries.isEmpty();
    }

    /** Текст для экрана патчера и отчёта (§7.5, §22). */
    public String toReportText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Файл: ").append(file.getName())
                .append(" (").append(sizeBytes).append(" байт)\n");
        sb.append("SHA-256: ").append(sha256).append('\n');
        sb.append("package: ").append(packageId)
                .append(", versionName: ").append(versionName)
                .append(", versionCode: ").append(versionCode).append('\n');
        sb.append("minSdk: ").append(minSdkVersion)
                .append(", targetSdk: ").append(targetSdkVersion).append('\n');
        sb.append("ABI: ").append(abis.isEmpty() ? "(не найдены)" : abis).append('\n');
        sb.append("DEX: ").append(dexEntries).append(" (")
                .append(dexTotalBytes).append(" байт)\n");
        sb.append("native-библиотек: ").append(nativeLibraries.size()).append('\n');
        sb.append("uses-permission: ").append(usesPermissions).append('\n');
        sb.append("provider authorities: ").append(providerAuthorities).append('\n');
        sb.append("подпись: ").append(signatureFiles.isEmpty() ? "(не найдена)" : signatureFiles).append('\n');
        sb.append("resources.arsc: ").append(hasResourcesArsc ? "да" : "нет").append('\n');
        sb.append("launcher: ").append(launcherActivities.isEmpty()
                ? "(не найден)" : launcherActivities).append('\n');
        sb.append("split: ").append(looksLikeSplit
                ? (splitName != null ? splitName : "похож на split (по имени файла)")
                : "нет (монолитный APK)").append('\n');
        if (alreadyPatched()) {
            sb.append("ВНИМАНИЕ: вход уже изменён платформой, маркеры: ").append(markerEntries).append('\n');
        }
        return sb.toString();
    }

    public static final class Builder {
        private final java.io.File file;
        private String packageId = "";
        private String versionName = "";
        private long versionCode;
        private Integer minSdkVersion;
        private Integer targetSdkVersion;
        private final Set<String> abis = new LinkedHashSet<>();
        private final List<String> dexEntries = new ArrayList<>();
        private long dexTotalBytes;
        private final List<String> nativeLibraries = new ArrayList<>();
        private final List<String> usesPermissions = new ArrayList<>();
        private final List<String> declaredPermissions = new ArrayList<>();
        private final List<String> providerAuthorities = new ArrayList<>();
        private final List<String> activityNames = new ArrayList<>();
        private String applicationName = "";
        private final List<String> signatureFiles = new ArrayList<>();
        private boolean hasResourcesArsc;
        private final List<String> markerEntries = new ArrayList<>();
        private String sha256 = "";
        private long sizeBytes;
        private String splitName;
        private boolean looksLikeSplit;
        private final List<String> launcherActivities = new ArrayList<>();

        private Builder(java.io.File file) {
            this.file = file;
        }

        public Builder packageId(String value) { this.packageId = value; return this; }
        public Builder versionName(String value) { this.versionName = value; return this; }
        public Builder versionCode(long value) { this.versionCode = value; return this; }
        public Builder minSdkVersion(Integer value) { this.minSdkVersion = value; return this; }
        public Builder targetSdkVersion(Integer value) { this.targetSdkVersion = value; return this; }
        public Builder addAbi(String value) { this.abis.add(value); return this; }
        public Builder addDex(String name, long size) {
            this.dexEntries.add(name);
            this.dexTotalBytes += size;
            return this;
        }
        public Builder addNativeLibrary(String value) { this.nativeLibraries.add(value); return this; }
        public Builder addUsesPermission(String value) { this.usesPermissions.add(value); return this; }
        public Builder addDeclaredPermission(String value) { this.declaredPermissions.add(value); return this; }
        public Builder addProviderAuthority(String value) { this.providerAuthorities.add(value); return this; }
        public Builder addActivityName(String value) { this.activityNames.add(value); return this; }
        public Builder applicationName(String value) { this.applicationName = value; return this; }
        public Builder addSignatureFile(String value) { this.signatureFiles.add(value); return this; }
        public Builder hasResourcesArsc(boolean value) { this.hasResourcesArsc = value; return this; }
        public Builder addMarker(String value) { this.markerEntries.add(value); return this; }
        public Builder sha256(String value) { this.sha256 = value; return this; }
        public Builder sizeBytes(long value) { this.sizeBytes = value; return this; }
        public Builder splitName(String value) { this.splitName = value; return this; }
        public Builder looksLikeSplit(boolean value) { this.looksLikeSplit = value; return this; }
        public Builder addLauncherActivity(String value) {
            if (value != null && !value.isEmpty()) {
                this.launcherActivities.add(value);
            }
            return this;
        }

        public ApkAnalysis build() {
            return new ApkAnalysis(this);
        }
    }
}
