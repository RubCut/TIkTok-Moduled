package dev.moduled.runtime;

import dev.moduled.core.AtomicFile;
import dev.moduled.core.DexValidator;
import dev.moduled.core.Hashing;
import dev.moduled.core.Result;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Импорт DEX в private storage хоста (ТЗ §5.2, §14.2).
 *
 * <p>Порядок: временный файл → проверка SHA-256 и заголовка DEX → атомарная
 * публикация → перевод в read-only. Для Android 14 при {@code targetSdk} 34+
 * динамически загружаемый файл обязан быть read-only, иначе загрузка запрещена
 * системой; порядок создания и проверки тестируется отдельно.</p>
 */
public final class DexImporter {

    private final File targetDirectory;

    public DexImporter(File targetDirectory) {
        this.targetDirectory = targetDirectory;
    }

    /**
     * Импортирует DEX из потока.
     *
     * @param moduleId         ID модуля (валидированный)
     * @param expectedSha256   SHA-256 из снимка
     * @param input            поток байтов DEX
     * @return файл в private storage или причина отказа
     */
    public Result<File> importDex(String moduleId, String expectedSha256, InputStream input) {
        Result<Path> targetPath = dev.moduled.core.ModulePaths.dexFile(
                targetDirectory.toPath().getParent() != null
                        ? targetDirectory.toPath()
                        : targetDirectory.toPath(), moduleId);
        if (!targetPath.isOk()) {
            return Result.fail(targetPath.errorCode(), targetPath.errorMessage());
        }
        if (!Hashing.isSha256Hex(expectedSha256)) {
            return Result.fail("DEX_BAD_EXPECTED_HASH",
                    "Snapshot hash for " + moduleId + " is not a SHA-256 value");
        }
        Path target = targetPath.value();
        Path temp = target.resolveSibling(target.getFileName() + ".importing");
        try {
            Files.createDirectories(target.getParent());
            long copied = Files.copy(input, temp,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (copied > dev.moduled.core.Limits.MAX_DEX_BYTES) {
                Files.deleteIfExists(temp);
                return Result.fail("DEX_TOO_LARGE",
                        "DEX of " + moduleId + " exceeds " + dev.moduled.core.Limits.MAX_DEX_BYTES);
            }
            byte[] data = Files.readAllBytes(temp);
            String actualHash = Hashing.sha256Hex(data);
            if (!actualHash.equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(temp);
                return Result.fail("DEX_HASH_MISMATCH",
                        "DEX hash mismatch for " + moduleId + ": expected " + expectedSha256
                                + ", got " + actualHash);
            }
            Result<Void> dexCheck = DexValidator.validate(data);
            if (!dexCheck.isOk()) {
                Files.deleteIfExists(temp);
                return Result.fail(dexCheck.errorCode(), dexCheck.errorMessage());
            }
            Result<Void> published = AtomicFile.write(target, data);
            Files.deleteIfExists(temp);
            if (!published.isOk()) {
                return Result.fail(published.errorCode(), published.errorMessage());
            }
            // Read-only до загрузки: требование Android 14+ (ТЗ §14.2 [S3]).
            File targetFile = target.toFile();
            boolean writable = targetFile.setWritable(false, true);
            if (!writable && targetFile.canWrite()) {
                return Result.fail("DEX_NOT_READ_ONLY",
                        "DEX file could not be made read-only: " + target);
            }
            return Result.ok(targetFile);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // очистка временного файла не критична
            }
            return Result.fail("DEX_IMPORT_FAILED", "Cannot import DEX for " + moduleId + ": " + e);
        }
    }
}
