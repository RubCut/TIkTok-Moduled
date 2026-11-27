package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DexValidatorTest {

    /** Минимально правдоподобный DEX-заголовок: 128 байт, map_off = 112. */
    static byte[] validDex() {
        byte[] dex = new byte[128];
        dex[0] = 'd';
        dex[1] = 'e';
        dex[2] = 'x';
        dex[3] = '\n';
        dex[4] = '0';
        dex[5] = '3';
        dex[6] = '5';
        dex[7] = 0;
        writeUInt(dex, 32, dex.length);   // file_size
        writeUInt(dex, 36, 0x70);         // header_size
        writeUInt(dex, 40, 0x12345678L);  // endian_tag
        writeUInt(dex, 52, 112);          // map_off
        return dex;
    }

    static void writeUInt(byte[] data, int offset, long value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    @Test
    public void acceptsWellFormedHeader() {
        Result<Void> result = DexValidator.validate(validDex());
        assertTrue(result.errorMessage(), result.isOk());
    }

    @Test
    public void rejectsTooSmallFile() {
        assertEquals("DEX_TOO_SMALL", DexValidator.validate(new byte[10]).errorCode());
        assertEquals("DEX_TOO_SMALL", DexValidator.validate(null).errorCode());
    }

    @Test
    public void rejectsBadMagic() {
        byte[] dex = validDex();
        dex[0] = 'x';
        assertEquals("DEX_BAD_MAGIC", DexValidator.validate(dex).errorCode());
    }

    @Test
    public void rejectsUnsupportedVersion() {
        byte[] dex = validDex();
        dex[4] = '9';
        dex[5] = '9';
        dex[6] = '9';
        assertEquals("DEX_UNSUPPORTED_VERSION", DexValidator.validate(dex).errorCode());
    }

    @Test
    public void acceptsKnownVersions() {
        for (String version : new String[] {"035", "037", "038", "039", "040"}) {
            byte[] dex = validDex();
            dex[4] = (byte) version.charAt(0);
            dex[5] = (byte) version.charAt(1);
            dex[6] = (byte) version.charAt(2);
            assertTrue("DEX " + version + " must be accepted", DexValidator.validate(dex).isOk());
        }
    }

    @Test
    public void rejectsBadHeaderSizeAndEndian() {
        byte[] badHeader = validDex();
        writeUInt(badHeader, 36, 0x60);
        assertEquals("DEX_BAD_HEADER_SIZE", DexValidator.validate(badHeader).errorCode());

        byte[] badEndian = validDex();
        writeUInt(badEndian, 40, 0x78563412L);
        assertEquals("DEX_BAD_ENDIAN", DexValidator.validate(badEndian).errorCode());
    }

    /** PKG-04: объявленный размер не может превышать реальный. */
    @Test
    public void rejectsDeclaredSizeMismatch() {
        byte[] dex = validDex();
        writeUInt(dex, 32, 4096);
        assertEquals("DEX_BAD_FILE_SIZE", DexValidator.validate(dex).errorCode());
    }

    @Test
    public void rejectsMapOffsetOutOfRange() {
        byte[] dex = validDex();
        writeUInt(dex, 52, 99999);
        assertEquals("DEX_BAD_MAP_OFFSET", DexValidator.validate(dex).errorCode());
    }
}
