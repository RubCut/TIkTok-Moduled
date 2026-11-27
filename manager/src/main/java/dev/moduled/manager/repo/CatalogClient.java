package dev.moduled.manager.repo;

import dev.moduled.core.ArchiveValidator;
import dev.moduled.core.CatalogIndex;
import dev.moduled.core.Hashing;
import dev.moduled.core.Limits;
import dev.moduled.core.ModuleManifest;
import dev.moduled.core.RemoteDescriptor;
import dev.moduled.core.Result;
import dev.moduled.core.UrlPolicy;
import java.io.File;
import java.io.IOException;

/**
 * Клиент каталога и репозиториев (ТЗ §13.1, §13.2).
 *
 * <p>Порядок установки из репозитория: скачать descriptor → скачать архив во
 * временный файл → проверить SHA-256 точных байтов → сравнить общие значимые
 * поля descriptor и манифеста архива → проверить пакет → передать в хранилище.
 * Скачанный код не исполняется до установки и явного включения (§13.2).</p>
 */
public final class CatalogClient {

    /** Таймаут по умолчанию: 15 секунд на соединение и чтение (§13.2). */
    public static final int DEFAULT_TIMEOUT_MS = 15_000;

    private final HttpFetcher fetcher;
    private final int timeoutMs;

    public CatalogClient(HttpFetcher fetcher) {
        this(fetcher, DEFAULT_TIMEOUT_MS);
    }

    public CatalogClient(HttpFetcher fetcher, int timeoutMs) {
        this.fetcher = fetcher;
        this.timeoutMs = timeoutMs;
    }

    public Result<CatalogIndex> fetchCatalog(String url, boolean[] cancel) {
        Result<Void> policy = UrlPolicy.check(url);
        if (!policy.isOk()) {
            return Result.fail(policy.errorCode(), policy.errorMessage());
        }
        byte[] data;
        try {
            data = fetcher.fetch(url, Limits.MAX_CATALOG_BYTES, timeoutMs,
                    UrlPolicy.MAX_REDIRECTS, cancel);
        } catch (IOException e) {
            return Result.fail("CATALOG_FETCH_FAILED", "Не удалось получить каталог: " + e.getMessage());
        }
        if (cancel != null && cancel.length > 0 && cancel[0]) {
            return Result.fail("CATALOG_CANCELLED", "Обновление каталога отменено");
        }
        Result<CatalogIndex> index = CatalogIndex.validate(data);
        if (!index.isOk()) {
            return Result.fail(index.errorCode(), index.errorMessage());
        }
        return index;
    }

    public Result<RemoteDescriptor> fetchDescriptor(String url, boolean[] cancel) {
        Result<Void> policy = UrlPolicy.check(url);
        if (!policy.isOk()) {
            return Result.fail(policy.errorCode(), policy.errorMessage());
        }
        byte[] data;
        try {
            data = fetcher.fetch(url, Limits.MAX_DESCRIPTOR_BYTES, timeoutMs,
                    UrlPolicy.MAX_REDIRECTS, cancel);
        } catch (IOException e) {
            return Result.fail("DESCRIPTOR_FETCH_FAILED",
                    "Не удалось получить descriptor: " + e.getMessage());
        }
        if (cancel != null && cancel.length > 0 && cancel[0]) {
            return Result.fail("DESCRIPTOR_CANCELLED", "Загрузка отменена");
        }
        return RemoteDescriptor.validate(data);
    }

    /**
     * Скачивает архив модуля во временный файл и проверяет его до установки.
     *
     * @param descriptor авторский descriptor с {@code downloadUrl} и {@code archiveSha256}
     * @param tempDir    каталог для временного файла (не каталог исполнения)
     * @param cancel     флаг отмены
     * @return файл архива, готовый к установке, или причина отказа
     */
    public Result<DownloadedModule> downloadModule(RemoteDescriptor descriptor, File tempDir,
                                                   boolean[] cancel) {
        if (descriptor == null) {
            return Result.fail("DOWNLOAD_NO_DESCRIPTOR", "Descriptor отсутствует");
        }
        Result<Void> policy = UrlPolicy.check(descriptor.downloadUrl);
        if (!policy.isOk()) {
            return Result.fail(policy.errorCode(), policy.errorMessage());
        }
        if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
            return Result.fail("DOWNLOAD_NO_TEMP_DIR",
                    "Не удалось создать временный каталог: " + tempDir);
        }
        File target = new File(tempDir, "download-" + descriptor.manifest.id + '-'
                + System.currentTimeMillis() + ".zip");
        long written;
        try {
            written = fetcher.fetchToFile(descriptor.downloadUrl, target,
                    Limits.MAX_DOWNLOAD_BYTES, timeoutMs, UrlPolicy.MAX_REDIRECTS, cancel);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return Result.fail("DOWNLOAD_FAILED", "Скачивание не удалось: " + e.getMessage());
        }
        if (cancel != null && cancel.length > 0 && cancel[0]) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return Result.fail("DOWNLOAD_CANCELLED", "Скачивание отменено пользователем");
        }
        String actualHash;
        try {
            actualHash = Hashing.sha256Hex(java.nio.file.Files.readAllBytes(target.toPath()));
        } catch (IOException e) {
            return Result.fail("DOWNLOAD_UNREADABLE", "Скачанный файл не читается: " + e.getMessage());
        }
        if (!actualHash.equalsIgnoreCase(descriptor.archiveSha256)) {
            // §13.2: подмена архива — отказ до установки.
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return Result.fail("DOWNLOAD_HASH_MISMATCH",
                    "SHA-256 архива не совпал: ожидался " + descriptor.archiveSha256
                            + ", получен " + actualHash + ". Установка отменена.");
        }
        Result<ModuleManifest> archiveManifest = ArchiveValidator.validateModulePackage(target.toPath());
        if (!archiveManifest.isOk()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return Result.fail(archiveManifest.errorCode(),
                    "Скачанный пакет не прошёл проверку: " + archiveManifest.errorMessage());
        }
        Result<Void> match = RemoteDescriptor.matchesArchiveManifest(descriptor, archiveManifest.value());
        if (!match.isOk()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return Result.fail(match.errorCode(), match.errorMessage());
        }
        return Result.ok(new DownloadedModule(target, descriptor, archiveManifest.value(),
                actualHash, written));
    }

    /** Скачанный и проверенный архив, готовый к установке. */
    public static final class DownloadedModule {
        public final File archive;
        public final RemoteDescriptor descriptor;
        public final ModuleManifest manifest;
        public final String archiveSha256;
        public final long sizeBytes;

        DownloadedModule(File archive, RemoteDescriptor descriptor, ModuleManifest manifest,
                         String archiveSha256, long sizeBytes) {
            this.archive = archive;
            this.descriptor = descriptor;
            this.manifest = manifest;
            this.archiveSha256 = archiveSha256;
            this.sizeBytes = sizeBytes;
        }
    }
}
