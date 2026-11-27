package dev.moduled.patcher;

import dev.moduled.core.Hashing;
import dev.moduled.core.Result;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Хранилище ключа подписи (ТЗ §7.8, §20.4).
 *
 * <p>Правила, которые класс обеспечивает:</p>
 * <ul>
 *   <li>Ключ создаётся один раз и сохраняется между патчами: обновления
 *       устанавлируются без удаления приложения.</li>
 *   <li>Файлы ключа лежат во внутреннем хранилище менеджера (не в
 *       {@code /storage/emulated/0/tmoduled}) и недоступны другим приложениям.</li>
 *   <li>Секретный ключ и пароль не пишутся в логи, отчёты и артефакты: наружу
 *       отдаётся только отпечаток сертификата (SHA-256, не секрет).</li>
 *   <li>Production-подпись отделена: этот класс создаёт только разработческий
 *       самоподписанный ключ и никогда не используется для production-профиля.</li>
 * </ul>
 */
public final class SigningKeys {

    public static final String KEYSTORE_FILE_NAME = "tmoduled-signing.p12";
    public static final String PASSWORD_FILE_NAME = "tmoduled-signing.pass";
    public static final String DEFAULT_ALIAS = "tmoduled";
    public static final String KEYSTORE_TYPE = "PKCS12";

    private static final int KEY_SIZE = 4096;
    private static final long VALIDITY_MS = 30L * 365 * 24 * 60 * 60 * 1000;

    private SigningKeys() {
    }

    /** Загруженный ключ: приватная часть остаётся в памяти процесса и не логируется. */
    public static final class Loaded {
        public final PrivateKey privateKey;
        public final X509Certificate certificate;
        public final String fingerprint;
        public final String alias;

        Loaded(PrivateKey privateKey, X509Certificate certificate, String fingerprint,
               String alias) {
            this.privateKey = privateKey;
            this.certificate = certificate;
            this.fingerprint = fingerprint;
            this.alias = alias;
        }
    }

    /**
     * Загружает существующий ключ или создаёт новый в {@code directory}.
     * Каталог обязан быть внутренним хранилищем приложения (§20.4).
     */
    public static Result<Loaded> loadOrCreate(File directory, String alias,
                                              SigningProfile.Kind kind) {
        if (kind == SigningProfile.Kind.PRODUCTION) {
            // §15.4: production-подпись подключается отдельно (ADR-0002);
            // автоматическое создание такого ключа запрещено.
            return Result.fail("SIGNING_PRODUCTION_FORBIDDEN",
                    "Production-ключ не создаётся автоматически: он поставляется "
                            + "отдельным решением (ADR-0002) и не хранится в репозитории");
        }
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            return Result.fail("SIGNING_NO_DIR", "Каталог ключа недоступен: " + directory);
        }
        File keystoreFile = new File(directory, KEYSTORE_FILE_NAME);
        File passwordFile = new File(directory, PASSWORD_FILE_NAME);
        try {
            if (keystoreFile.isFile()) {
                return load(keystoreFile, passwordFile, alias);
            }
            return create(keystoreFile, passwordFile, alias);
        } catch (Exception e) {
            return Result.fail("SIGNING_FAILED", "Операция с ключом подписи не удалась: "
                    + e.getClass().getSimpleName() + " " + safeMessage(e));
        }
    }

    private static Result<Loaded> load(File keystoreFile, File passwordFile, String alias)
            throws Exception {
        if (!passwordFile.isFile()) {
            return Result.fail("SIGNING_PASSWORD_MISSING",
                    "Хранилище ключа есть, но пароль утерян: обновления этим ключом невозможны. "
                            + "Создайте новый ключ (установка потребует удаления приложения).");
        }
        char[] password = readPassword(passwordFile);
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        try (InputStream in = new FileInputStream(keystoreFile)) {
            keyStore.load(in, password);
        }
        if (!keyStore.containsAlias(alias)) {
            return Result.fail("SIGNING_ALIAS_MISSING",
                    "В хранилище нет алиаса " + alias);
        }
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, password);
        Certificate certificate = keyStore.getCertificate(alias);
        if (privateKey == null || certificate == null) {
            return Result.fail("SIGNING_INCOMPLETE", "Ключ или сертификат повреждены");
        }
        return Result.ok(new Loaded(privateKey, (X509Certificate) certificate,
                fingerprintOf((X509Certificate) certificate), alias));
    }

    private static Result<Loaded> create(File keystoreFile, File passwordFile, String alias)
            throws Exception {
        char[] password = randomPassword();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();

        long now = System.currentTimeMillis();
        X500Name subject = new X500Name("CN=TModules Development Key, O=TModules, C=SE");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                new Date(now - 24L * 60 * 60 * 1000),
                new Date(now + VALIDITY_MS),
                subject,
                pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
        X509Certificate certificate =
                new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        keyStore.load(null, null);
        keyStore.setKeyEntry(alias, pair.getPrivate(), password, new Certificate[] {certificate});

        File tempKeystore = new File(keystoreFile.getParentFile(), keystoreFile.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tempKeystore)) {
            keyStore.store(out, password);
            out.flush();
        }
        if (!tempKeystore.renameTo(keystoreFile)) {
            //noinspection ResultOfMethodCallIgnored
            tempKeystore.delete();
            return Result.fail("SIGNING_STORE_FAILED", "Не удалось сохранить хранилище ключа");
        }
        // Пароль пишется атомарно и с ограничением прав (§20.4, §23.2).
        File tempPassword = new File(passwordFile.getParentFile(), passwordFile.getName() + ".tmp");
        Files.write(tempPassword.toPath(), new String(password).getBytes(StandardCharsets.UTF_8));
        if (!tempPassword.renameTo(passwordFile)) {
            //noinspection ResultOfMethodCallIgnored
            tempPassword.delete();
            //noinspection ResultOfMethodCallIgnored
            keystoreFile.delete();
            return Result.fail("SIGNING_STORE_FAILED", "Не удалось сохранить пароль ключа");
        }
        restrict(keystoreFile);
        restrict(passwordFile);
        return Result.ok(new Loaded(pair.getPrivate(), certificate,
                fingerprintOf(certificate), alias));
    }

    private static void restrict(File file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file.toPath(), permissions);
        } catch (IOException | UnsupportedOperationException e) {
            // Не POSIX-файловая система (эмулятор/Windows в тестах):
            // защита обеспечивается внутренним хранилищем приложения.
        }
    }

    private static char[] randomPassword() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bytes).toCharArray();
    }

    private static char[] readPassword(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .trim().toCharArray();
    }

    /** SHA-256 DER-представления сертификата: открытая информация для UI и отчётов. */
    public static String fingerprintOf(X509Certificate certificate) throws Exception {
        byte[] der = certificate.getEncoded();
        String hex = Hashing.sha256Hex(new ByteArrayInputStream(der), der.length);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (grouped.length() > 0) {
                grouped.append(':');
            }
            grouped.append(hex, i, i + 2);
        }
        return grouped.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /** Отпечаток из существующего keystore без создания нового ключа. */
    public static Result<String> fingerprintOf(File keystoreFile, File passwordFile, String alias) {
        try {
            Result<Loaded> loaded = load(keystoreFile, passwordFile, alias);
            return loaded.isOk()
                    ? Result.ok(loaded.value().fingerprint)
                    : Result.fail(loaded.errorCode(), loaded.errorMessage());
        } catch (Exception e) {
            return Result.fail("SIGNING_FAILED", "Не удалось прочитать отпечаток: "
                    + safeMessage(e));
        }
    }

    /**
     * Сообщение об ошибке без секретов: пароль и ключ никогда не должны попадать
     * в тексты ошибок (§7.8).
     */
    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null) {
            return "";
        }
        String sanitized = message.replaceAll("(?i)(password|passphrase|secret)\\s*=\\S+", "$1=<скрыто>");
        return sanitized.length() > 300 ? sanitized.substring(0, 300) + "…" : sanitized;
    }
}
