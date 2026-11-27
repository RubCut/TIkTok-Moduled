package dev.moduled.patcher;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Профиль подписи (ТЗ §7.8, §15.4, §28).
 *
 * <p>Два раздельных режима:</p>
 * <ul>
 *   <li>{@link Kind#DEVELOPMENT_SELF_SIGNED} — самоподписанный ключ, создаётся на
 *       устройстве и хранится во внутреннем хранилище менеджера. Он сохраняется
 *       между патчами, чтобы обновления устанавливались без удаления.</li>
 *   <li>{@link Kind#PRODUCTION} — ключ выпуска, который НИКОГДА не лежит в
 *       исходниках, логах или артефактах сборки; подключается отдельным решением
 *       (ADR-0002) и отдельным хранилищем.</li>
 * </ul>
 */
public final class SigningProfile {

    public enum Kind {
        DEVELOPMENT_SELF_SIGNED,
        PRODUCTION
    }

    public final Kind kind;
    public final File keyStoreFile;
    public final String keyAlias;
    /** Отпечаток сертификата (SHA-256): показывается пользователю, секретом не является. */
    public final String certificateFingerprint;
    public final List<String> signatureSchemeVersions;
    /** ADR, которым утверждён режим подписи (§15.4). */
    public final String adrReference;

    private SigningProfile(Kind kind, File keyStoreFile, String keyAlias,
                           String certificateFingerprint, List<String> schemes,
                           String adrReference) {
        this.kind = kind;
        this.keyStoreFile = keyStoreFile;
        this.keyAlias = keyAlias;
        this.certificateFingerprint = certificateFingerprint;
        this.signatureSchemeVersions = Collections.unmodifiableList(new ArrayList<>(schemes));
        this.adrReference = adrReference;
    }

    public static SigningProfile development(File keyStoreFile, String alias,
                                             String fingerprint) {
        return new SigningProfile(Kind.DEVELOPMENT_SELF_SIGNED, keyStoreFile, alias,
                fingerprint, List.of("v1", "v2", "v3"), "ADR-0002");
    }

    public static SigningProfile production(File keyStoreFile, String alias, String fingerprint,
                                            List<String> schemes, String adr) {
        return new SigningProfile(Kind.PRODUCTION, keyStoreFile, alias, fingerprint, schemes, adr);
    }

    public boolean isProduction() {
        return kind == Kind.PRODUCTION;
    }

    /** Текст для экрана патчера: режим и отпечаток, без секретов (§7.8). */
    public String toText() {
        return "Режим подписи: " + (isProduction()
                ? "production (отделённая подпись, " + adrReference + ')'
                : "разработческий самоподписанный ключ (" + adrReference + ')')
                + "\n  хранилище: " + keyStoreFile
                + "\n  алиас: " + keyAlias
                + "\n  отпечаток сертификата: " + certificateFingerprint
                + "\n  схемы: " + signatureSchemeVersions;
    }
}
