package dev.moduled.core;

/**
 * Минимальная валидность DEX-заголовка (ТЗ §10.3 PKG-04).
 * Валидный заголовок НЕ доказывает безопасность кода — это только отсекает
 * заведомо не-DEX файлы до загрузки.
 */
public final class DexValidator {
    private DexValidator() {
    }

    public static Result<Void> validate(byte[] dex) {
        if (dex == null || dex.length < 112) {
            return Result.fail("DEX_TOO_SMALL", "DEX file is smaller than the 112-byte header");
        }
        // Magic: "dex\n" + 3 цифры версии + \0.
        if (dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n' || dex[7] != 0) {
            return Result.fail("DEX_BAD_MAGIC", "DEX magic is invalid");
        }
        String version = new String(dex, 4, 3, java.nio.charset.StandardCharsets.US_ASCII);
        if (!version.equals("035") && !version.equals("037") && !version.equals("038")
                && !version.equals("039") && !version.equals("040")) {
            return Result.fail("DEX_UNSUPPORTED_VERSION", "Unsupported DEX version: " + version);
        }
        // Header size = 0x70, endian tag = 0x12345678 (little-endian DEX).
        long headerSize = readUInt(dex, 36);
        if (headerSize != 0x70) {
            return Result.fail("DEX_BAD_HEADER_SIZE", "DEX header size is not 0x70: " + headerSize);
        }
        long endianTag = readUInt(dex, 40);
        if (endianTag != 0x12345678L) {
            return Result.fail("DEX_BAD_ENDIAN", "DEX endian tag is not little-endian: " + endianTag);
        }
        // Declared file size must not exceed actual size.
        long fileSize = readUInt(dex, 32);
        if (fileSize == 0 || fileSize > dex.length) {
            return Result.fail("DEX_BAD_FILE_SIZE",
                    "DEX declared size " + fileSize + " does not match actual " + dex.length);
        }
        // Link size / map offset sanity: map must be inside the file.
        long mapOff = readUInt(dex, 52);
        if (mapOff != 0 && (mapOff < 112 || mapOff >= dex.length)) {
            return Result.fail("DEX_BAD_MAP_OFFSET", "DEX map offset out of range: " + mapOff);
        }
        return Result.ok(null);
    }

    private static long readUInt(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }
}
