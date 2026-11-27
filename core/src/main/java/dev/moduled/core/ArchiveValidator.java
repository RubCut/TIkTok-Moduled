package dev.moduled.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Безопасная проверка ZIP-пакета модуля (ТЗ §10.3 PKG-01..PKG-05).
 *
 * <p>Отклоняются: не-ZIP, повреждённый CRC, неподдерживаемый метод сжатия,
 * шифрование, абсолютные пути, сегменты {@code ..} и {@code .}, неоднозначные
 * разделители, дубликаты имён, пустые имена, превышение размера архива,
 * отдельной записи и суммарного распакованного объёма.</p>
 *
 * <p>Проверка идёт по реально распакованному потоку байт, а не только по metadata
 * архива: заявленные размеры могут не совпадать с фактическими.</p>
 */
public final class ArchiveValidator {
    private ArchiveValidator() {
    }

    /** Результат проверки: имена записей и их распакованные байты. */
    public static final class ValidatedArchive {
        public final Map<String, byte[]> entries;

        public ValidatedArchive(Map<String, byte[]> entries) {
            this.entries = entries;
        }

        public byte[] get(String name) {
            return entries.get(name);
        }

        public Set<String> names() {
            return entries.keySet();
        }
    }

    public static Result<ValidatedArchive> validate(Path zipPath) {
        if (zipPath == null || !Files.isRegularFile(zipPath)) {
            return Result.fail("ARCHIVE_MISSING", "Archive file does not exist: " + zipPath);
        }
        long fileSize;
        try {
            fileSize = Files.size(zipPath);
        } catch (IOException e) {
            return Result.fail("ARCHIVE_UNREADABLE", "Cannot stat archive: " + e.getMessage());
        }
        if (fileSize > Limits.MAX_ARCHIVE_FILE_BYTES) {
            return Result.fail("ARCHIVE_TOO_LARGE",
                    "Archive is " + fileSize + " bytes, limit is " + Limits.MAX_ARCHIVE_FILE_BYTES);
        }

        // Шифрование и недопустимые имена определяются до открытия архива:
        // java.util.zip бросает общее исключение, которое не различает причины.
        Result<List<LocalHeader>> scan = scanLocalHeaders(zipPath);
        if (!scan.isOk()) {
            return Result.fail(scan.errorCode(), scan.errorMessage());
        }
        for (LocalHeader header : scan.value()) {
            if (header.encrypted) {
                return Result.fail("ARCHIVE_ENCRYPTED", "Encrypted entry: " + header.name);
            }
        }

        Map<String, byte[]> entries = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        long totalUnpacked = 0;

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            List<ZipEntry> zipEntries = new ArrayList<>();
            zip.stream().forEach(zipEntries::add);
            for (ZipEntry entry : zipEntries) {
                String name = entry.getName();
                Result<Void> nameCheck = checkEntryName(name);
                if (!nameCheck.isOk()) {
                    return Result.fail(nameCheck.errorCode(), nameCheck.errorMessage());
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (!seen.add(name)) {
                    return Result.fail("ARCHIVE_DUPLICATE_ENTRY", "Duplicate entry: " + name);
                }
                int method = entry.getMethod();
                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                    return Result.fail("ARCHIVE_UNSUPPORTED_METHOD",
                            "Unsupported compression method for entry: " + name);
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = readLimited(in, entryLimitFor(name), name);
                } catch (EntryTooLargeException e) {
                    // PKG-03: отдельный код, чтобы UI показал лимит, а не «повреждён».
                    return Result.fail("ARCHIVE_ENTRY_TOO_LARGE", e.getMessage());
                } catch (IOException e) {
                    return Result.fail("ARCHIVE_CORRUPT",
                            "Entry '" + name + "' is corrupt or has a CRC mismatch: " + e.getMessage());
                }
                totalUnpacked += data.length;
                if (totalUnpacked > Limits.MAX_TOTAL_UNPACKED_BYTES) {
                    return Result.fail("ARCHIVE_TOO_LARGE", "Total unpacked size exceeds "
                            + Limits.MAX_TOTAL_UNPACKED_BYTES + " bytes");
                }
                entries.put(name, data);
            }
        } catch (IOException e) {
            return Result.fail("ARCHIVE_CORRUPT", "Not a valid ZIP archive: " + e.getMessage());
        }
        return Result.ok(new ValidatedArchive(entries));
    }

    /**
     * Проверка пакета модуля v1 (ТЗ §10.1): ровно {@code module.json} и
     * {@code classes.dex} в корне архива. Ресурсы, дополнительные DEX, каталоги
     * и native-библиотеки — только в будущей версии формата v2.
     */
    public static Result<ModuleManifest> validateModulePackage(Path zipPath) {
        Result<ValidatedArchive> archive = validate(zipPath);
        if (!archive.isOk()) {
            return Result.fail(archive.errorCode(), archive.errorMessage());
        }
        Map<String, byte[]> entries = archive.value().entries;
        if (entries.size() != 2 || !entries.containsKey("module.json")
                || !entries.containsKey("classes.dex")) {
            return Result.fail("PACKAGE_LAYOUT",
                    "Module v1 package must contain exactly module.json and classes.dex in root, got: "
                            + entries.keySet());
        }
        Result<ModuleManifest> manifest = ManifestValidator.validate(entries.get("module.json"));
        if (!manifest.isOk()) {
            return Result.fail(manifest.errorCode(), manifest.errorMessage());
        }
        Result<Void> dex = DexValidator.validate(entries.get("classes.dex"));
        if (!dex.isOk()) {
            return Result.fail(dex.errorCode(), dex.errorMessage());
        }
        return manifest;
    }

    private static long entryLimitFor(String name) {
        if ("module.json".equals(name)) {
            return Limits.MAX_DESCRIPTOR_BYTES;
        }
        return Limits.MAX_DEX_BYTES;
    }

    private static Result<Void> checkEntryName(String name) {
        if (name == null || name.isEmpty()) {
            return Result.fail("ARCHIVE_BAD_NAME", "Empty entry name");
        }
        if (name.startsWith("/") || name.startsWith("\\")) {
            return Result.fail("ARCHIVE_BAD_NAME", "Absolute path entry: " + name);
        }
        if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            return Result.fail("ARCHIVE_BAD_NAME", "Ambiguous separator in entry: " + name);
        }
        String[] segments = name.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if ("..".equals(segment) || ".".equals(segment)) {
                return Result.fail("ARCHIVE_PATH_TRAVERSAL", "Path traversal in entry: " + name);
            }
            // Пустой сегмент допустим только как признак каталога в конце имени.
            if (segment.isEmpty() && i != segments.length - 1) {
                return Result.fail("ARCHIVE_BAD_NAME", "Empty path segment in entry: " + name);
            }
        }
        return Result.ok(null);
    }

    private static byte[] readLimited(InputStream in, long limit, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new EntryTooLargeException(
                        "Entry '" + name + "' exceeds limit of " + limit + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Превышен лимит распакованного размера отдельной записи (PKG-03). */
    private static final class EntryTooLargeException extends IOException {
        EntryTooLargeException(String message) {
            super(message);
        }
    }

    // --- Сканирование локальных заголовков ------------------------------------

    private static final class LocalHeader {
        final String name;
        final boolean encrypted;

        LocalHeader(String name, boolean encrypted) {
            this.name = name;
            this.encrypted = encrypted;
        }
    }

    private static Result<List<LocalHeader>> scanLocalHeaders(Path zipPath) {
        List<LocalHeader> headers = new ArrayList<>();
        try (InputStream in = Files.newInputStream(zipPath)) {
            byte[] header = new byte[30];
            while (true) {
                int read = readFully(in, header);
                if (read < 4) {
                    break;
                }
                long signature = readUIntLE(header, 0);
                if (signature == 0x04034B50L) {
                    if (read < 30) {
                        return Result.fail("ARCHIVE_CORRUPT", "Truncated local file header");
                    }
                    int flags = (header[6] & 0xFF) | ((header[7] & 0xFF) << 8);
                    int nameLen = (header[26] & 0xFF) | ((header[27] & 0xFF) << 8);
                    int extraLen = (header[28] & 0xFF) | ((header[29] & 0xFF) << 8);
                    long compressedSize = readUIntLE(header, 18);
                    byte[] nameBytes = new byte[nameLen];
                    if (readFully(in, nameBytes) != nameLen) {
                        return Result.fail("ARCHIVE_CORRUPT", "Truncated entry name");
                    }
                    if (!skipFully(in, extraLen)) {
                        return Result.fail("ARCHIVE_CORRUPT", "Truncated extra field");
                    }
                    String name = new String(nameBytes, StandardCharsets.UTF_8);
                    headers.add(new LocalHeader(name, (flags & 0x1) != 0));
                    // Data descriptor (флаг 3) означает размер в trailer'е — такие
                    // архивы java.util.zip читает, но наше сканирование не может
                    // пропустить данные; сообщаем явно.
                    if ((flags & 0x8) != 0) {
                        return Result.fail("ARCHIVE_UNSUPPORTED_METHOD",
                                "Streaming entry with data descriptor is not supported: " + name);
                    }
                    if (!skipFully(in, compressedSize)) {
                        return Result.fail("ARCHIVE_CORRUPT",
                                "Truncated entry data: " + name);
                    }
                } else if (signature == 0x02014B50L || signature == 0x06054B50L) {
                    break; // Центральный каталог / EOCD: данные записей закончились.
                } else {
                    return Result.fail("ARCHIVE_CORRUPT",
                            "Unexpected ZIP signature 0x" + Long.toHexString(signature));
                }
            }
        } catch (IOException e) {
            return Result.fail("ARCHIVE_UNREADABLE", "Cannot read archive: " + e.getMessage());
        }
        if (headers.isEmpty()) {
            return Result.fail("ARCHIVE_CORRUPT", "No local file headers found in archive");
        }
        return Result.ok(headers);
    }

    private static long readUIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read == -1) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static boolean skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        byte[] scratch = new byte[8192];
        while (remaining > 0) {
            int read = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (read == -1) {
                return false;
            }
            remaining -= read;
        }
        return true;
    }
}
