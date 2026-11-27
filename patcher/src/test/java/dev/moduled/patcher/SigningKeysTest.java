package dev.moduled.patcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.moduled.core.Result;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * ТЗ §7.8, §20.4: ключ подписи создаётся один раз и переиспользуется
 * (обновления ставятся без удаления); production-ключ не создаётся
 * автоматически; секрет не попадает в тексты ошибок.
 */
public class SigningKeysTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void createsAndReloadsSameKey() throws Exception {
        File dir = folder.newFolder("keys");
        Result<SigningKeys.Loaded> first = SigningKeys.loadOrCreate(
                dir, SigningKeys.DEFAULT_ALIAS, SigningProfile.Kind.DEVELOPMENT_SELF_SIGNED);
        assertTrue(first.errorMessage(), first.isOk());
        assertNotNull(first.value().privateKey);
        assertNotNull(first.value().certificate);
        assertTrue(first.value().fingerprint.contains(":"));

        Result<SigningKeys.Loaded> second = SigningKeys.loadOrCreate(
                dir, SigningKeys.DEFAULT_ALIAS, SigningProfile.Kind.DEVELOPMENT_SELF_SIGNED);
        assertTrue(second.errorMessage(), second.isOk());
        // Тот же ключ: обновления можно ставить без удаления приложения.
        assertEquals(first.value().fingerprint, second.value().fingerprint);
        assertEquals(first.value().certificate, second.value().certificate);
    }

    @Test
    public void productionKeyIsNeverAutoCreated() throws Exception {
        File dir = folder.newFolder("prod");
        Result<SigningKeys.Loaded> result = SigningKeys.loadOrCreate(
                dir, SigningKeys.DEFAULT_ALIAS, SigningProfile.Kind.PRODUCTION);
        assertFalse(result.isOk());
        assertEquals("SIGNING_PRODUCTION_FORBIDDEN", result.errorCode());
    }

    @Test
    public void lostPasswordIsReportedNotSilentlyRegenerated() throws Exception {
        File dir = folder.newFolder("keys2");
        Result<SigningKeys.Loaded> created = SigningKeys.loadOrCreate(
                dir, SigningKeys.DEFAULT_ALIAS, SigningProfile.Kind.DEVELOPMENT_SELF_SIGNED);
        assertTrue(created.isOk());
        File password = new File(dir, SigningKeys.PASSWORD_FILE_NAME);
        assertTrue(password.isFile());
        assertTrue(password.delete());

        Result<SigningKeys.Loaded> reloaded = SigningKeys.loadOrCreate(
                dir, SigningKeys.DEFAULT_ALIAS, SigningProfile.Kind.DEVELOPMENT_SELF_SIGNED);
        assertFalse("без пароля ключ не должен молча пересоздаваться", reloaded.isOk());
        assertEquals("SIGNING_PASSWORD_MISSING", reloaded.errorCode());
    }

    @Test
    public void keystoreAndPasswordAreStoredInGivenDirectory() throws Exception {
        File dir = folder.newFolder("keys3");
        Result<SigningKeys.Loaded> created = SigningKeys.loadOrCreate(
                dir, SigningKeys.DEFAULT_ALIAS, SigningProfile.Kind.DEVELOPMENT_SELF_SIGNED);
        assertTrue(created.isOk());
        File keystore = new File(dir, SigningKeys.KEYSTORE_FILE_NAME);
        assertTrue(keystore.isFile());
        // Содержимое keystore не является текстом с паролем в открытом виде:
        // пароль хранится отдельно и не дублируется в отчёты.
        byte[] bytes = Files.readAllBytes(keystore.toPath());
        String password = new String(Files.readAllBytes(
                new File(dir, SigningKeys.PASSWORD_FILE_NAME).toPath()),
                StandardCharsets.UTF_8).trim();
        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(password));
    }
}
