package dev.moduled.runtime;

/** Валидный DEX-заголовок для тестов: 128 байт, map_off = 112. */
final class TestDex {
    private TestDex() {
    }

    static byte[] validHeader() {
        byte[] dex = new byte[128];
        dex[0] = 'd';
        dex[1] = 'e';
        dex[2] = 'x';
        dex[3] = '\n';
        dex[4] = '0';
        dex[5] = '3';
        dex[6] = '5';
        dex[7] = 0;
        writeUInt(dex, 32, dex.length);
        writeUInt(dex, 36, 0x70);
        writeUInt(dex, 40, 0x12345678L);
        writeUInt(dex, 52, 112);
        return dex;
    }

    private static void writeUInt(byte[] data, int offset, long value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
