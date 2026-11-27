package dev.moduled.manager.storage;

import java.io.File;

/**
 * Запрос установки (ТЗ §6.3, §11, PKG-05/PKG-06).
 *
 * <p>Новый сторонний модуль по целевому правилу устанавливается выключенным до
 * явного согласия. Замена существующего ID и смена авторского ключа требуют
 * отдельных подтверждений: ID сам по себе не доказывает того же автора.</p>
 */
public final class InstallRequest {

    public final File archive;
    public final ModuleState.Origin origin;
    public final String sourceUrl;
    public final String archiveSha256;
    public final String authorKeyFingerprint;
    public final boolean trustConfirmed;
    public final boolean confirmReplace;
    public final boolean confirmNewKey;
    public final boolean enableAfterInstall;
    public final boolean bundled;

    private InstallRequest(Builder builder) {
        this.archive = builder.archive;
        this.origin = builder.origin;
        this.sourceUrl = builder.sourceUrl;
        this.archiveSha256 = builder.archiveSha256;
        this.authorKeyFingerprint = builder.authorKeyFingerprint;
        this.trustConfirmed = builder.trustConfirmed;
        this.confirmReplace = builder.confirmReplace;
        this.confirmNewKey = builder.confirmNewKey;
        this.enableAfterInstall = builder.enableAfterInstall;
        this.bundled = builder.bundled;
    }

    public static Builder fromFile(File archive) {
        return new Builder(archive, ModuleState.Origin.LOCAL_FILE);
    }

    public static Builder fromRepository(File archive, String sourceUrl) {
        return new Builder(archive, ModuleState.Origin.REPOSITORY).sourceUrl(sourceUrl);
    }

    public static Builder bundled(File archive) {
        return new Builder(archive, ModuleState.Origin.BUNDLED).bundled(true);
    }

    public static final class Builder {
        private final File archive;
        private final ModuleState.Origin origin;
        private String sourceUrl = "";
        private String archiveSha256 = "";
        private String authorKeyFingerprint = "";
        private boolean trustConfirmed;
        private boolean confirmReplace;
        private boolean confirmNewKey;
        private boolean enableAfterInstall;
        private boolean bundled;

        private Builder(File archive, ModuleState.Origin origin) {
            if (archive == null) {
                throw new IllegalArgumentException("archive must not be null");
            }
            this.archive = archive;
            this.origin = origin;
        }

        public Builder sourceUrl(String value) { this.sourceUrl = value != null ? value : ""; return this; }
        public Builder archiveSha256(String value) { this.archiveSha256 = value != null ? value : ""; return this; }
        public Builder authorKeyFingerprint(String value) {
            this.authorKeyFingerprint = value != null ? value : "";
            return this;
        }
        public Builder trustConfirmed(boolean value) { this.trustConfirmed = value; return this; }
        public Builder confirmReplace(boolean value) { this.confirmReplace = value; return this; }
        public Builder confirmNewKey(boolean value) { this.confirmNewKey = value; return this; }
        public Builder enableAfterInstall(boolean value) { this.enableAfterInstall = value; return this; }
        public Builder bundled(boolean value) { this.bundled = value; return this; }

        public InstallRequest build() {
            return new InstallRequest(this);
        }
    }
}
