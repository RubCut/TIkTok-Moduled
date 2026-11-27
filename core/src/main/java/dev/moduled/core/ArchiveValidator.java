package dev.moduled.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Безопасная проверка ZIP-пакета модуля (ТЗ §10.3 PKG-01..PKG-05).
 *
 * <p>Отклоняются: не-ZIP, несовпадение CRC или размера, неподдерживаемый метод
 * сжатия, шифрование, абсолютные пути, сегменты {@code ..} и {@code .},
 * неоднозначные разделители, дубликаты имён, пустые имена, превышение размера
 * архива, отдельной записи и суммарного распакованного объёма.</p>
 *
 * <p>CRC и размер проверяются по фактически прочитанному потоку байтов, а не по
 * metadata: {@code java.util.zip.ZipFile} для STORED-записей CRC не сверяет,
 * поэтому проверка выполняется явно (PKG-02). Шифрование определяется по флагам
 * центрального каталога до открытия архива.</p>
 */
public final class ArchiveValidator {

    private static final long EOCD_SIGNATURE = 0x06054B50L;
    private static final long CD_SIGNATURE = 0x02014B50L;
    private static final int EOCD_MIN_SIZE = 22;
    private static final int EOCD_MAX_COMMENT = 0xFFFF;

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

        Result<List<CentralEntry>> scan = scanCentralDirectory(zipPath);
        if (!scan.isOk()) {
            return Result.fail(scan.errorCode(), scan.errorMessage());
        }
        for (CentralEntry entry : scan.value()) {
            if (entry.encrypted) {
                return Result.fail("ARCHIVE_ENCRYPTED", "Encrypted entry: " + entry.name);
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
                            "Unsupported compression method (" + method + ") for entry: " + name);
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = readAndVerify(in, entry, name);
                } catch (EntryTooLargeException e) {
                    // PKG-03: отдельный код, чтобы UI показал лимит, а не «повреждён».
                    return Result.fail("ARCHIVE_ENTRY_TOO_LARGE", e.getMessage());
                } catch (IOException e) {
                    return Result.fail("ARCHIVE_CORRUPT",
                            "Entry '" + name + "' is corrupt: " + e.getMessage());
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

    /**
     * Читает запись полностью и сверяет фактический CRC-32 и размер с заявленными
     * (PKG-02). Одновременно следит за лимитом распакованных байтов (PKG-03).
     */
    private static byte[] readAndVerify(InputStream in, ZipEntry entry, String name)
            throws IOException {
        long limit = entryLimitFor(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new EntryTooLargeException(
                        "Entry '" + name + "' exceeds limit of " + limit + " bytes");
            }
            crc.update(buffer, 0, read);
            out.write(buffer, 0, read);
        }
        long declaredSize = entry.getSize();
        if (declaredSize >= 0 && declaredSize != total) {
            throw new IOException("declared size " + declaredSize + " != actual " + total);
        }
        long declaredCrc = entry.getCrc();
        if (declaredCrc >= 0 && declaredCrc != crc.getValue()) {
            throw new IOException("CRC mismatch (declared 0x" + Long.toHexString(declaredCrc)
                    + ", actual 0x" + Long.toHexString(crc.getValue()) + ')');
        }
        return out.toByteArray();
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

    // --- Центральный каталог --------------------------------------------------

    private static final class CentralEntry {
        final String name;
        final boolean encrypted;

        CentralEntry(String name, boolean encrypted) {
            this.name = name;
            this.encrypted = encrypted;
        }
    }

    /**
     * Читает центральный каталог, чтобы увидеть флаги записей (шифрование) и
     * имена до открытия архива. Zip64 не поддерживается: при лимите 64 MiB он
     * не нужен, а его наличие говорит о нестандартном архиве.
     */
    private static Result<List<CentralEntry>> scanCentralDirectory(Path zipPath) {
        try (RandomAccessFile raf = new RandomAccessFile(zipPath.toFile(), "r")) {
            long length = raf.length();
            if (length < EOCD_MIN_SIZE) {
                return Result.fail("ARCHIVE_CORRUPT",
                        "File is too small to be a ZIP archive: " + length + " bytes");
            }
            int searchSize = (int) Math.min(length, EOCD_MIN_SIZE + EOCD_MAX_COMMENT);
            byte[] tail = new byte[searchSize];
            raf.seek(length - searchSize);
            raf.readFully(tail);

            long eocdOffsetInTail = -1;
            for (int i = tail.length - EOCD_MIN_SIZE; i >= 0; i--) {
                if (readUIntLE(tail, i) == EOCD_SIGNATURE) {
                    eocdOffsetInTail = i;
                    break;
                }
            }
            if (eocdOffsetInTail < 0) {
                return Result.fail("ARCHIVE_CORRUPT",
                        "End of central directory record not found");
            }
            int eocd = (int) eocdOffsetInTail;
            int entryCount = readUShort(tail, eocd + 10);
            long cdSize = readUIntLE(tail, eocd + 12);
            long cdOffset = readUIntLE(tail, eocd + 16);
            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || entryCount == 0xFFFF) {
                return Result.fail("ARCHIVE_UNSUPPORTED_METHOD",
                        "Zip64 archives are not supported and are not needed under the "
                                + Limits.MAX_ARCHIVE_FILE_BYTES + " byte limit");
            }
            if (cdOffset + cdSize > length) {
                return Result.fail("ARCHIVE_CORRUPT",
                        "Central directory offset/size point outside the file");
            }

            List<CentralEntry> entries = new ArrayList<>(entryCount);
            raf.seek(cdOffset);
            byte[] header = new byte[46];
            for (int i = 0; i < entryCount; i++) {
                raf.readFully(header);
                if (readUIntLE(header, 0) != CD_SIGNATURE) {
                    return Result.fail("ARCHIVE_CORRUPT",
                            "Bad central directory signature at entry " + i);
                }
                int flags = readUShort(header, 8);
                int nameLen = readUShort(header, 28);
                int extraLen = readUShort(header, 30);
                int commentLen = readUShort(header, 32);
                byte[] nameBytes = new byte[nameLen];
                raf.readFully(nameBytes);
                if (extraLen > 0) {
                    raf.skipBytes(extraLen);
                }
                if (commentLen > 0) {
                    raf.skipBytes(commentLen);
                }
                entries.add(new CentralEntry(
                        new String(nameBytes, StandardCharsets.UTF_8), (flags & 0x1) != 0));
            }
            // Пустой архив формально валиден: отклонит его проверка состава пакета.
            return Result.ok(entries);
        } catch (IOException e) {
            return Result.fail("ARCHIVE_UNREADABLE", "Cannot read archive: " + e.getMessage());
        }
    }

    private static long readUIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static int readUShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    /** Превышен лимит распакованного размера отдельной записи (PKG-03). */
    private static final class EntryTooLargeException extends IOException {
        EntryTooLargeException(String message) {
            super(message);
        }
    }
}
