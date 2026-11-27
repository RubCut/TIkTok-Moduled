package dev.moduled.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Тестовый сборщик ZIP без внешних зависимостей: метод STORED, полный контроль
 * над именами записей, флагами (включая шифрование) и CRC. Нужен, чтобы покрыть
 * PKG-01/PKG-02 сценариями, которые обычный ZipOutputStream создать не позволяет.
 */
final class TestZip {
    private TestZip() {
    }

    static final class Entry {
        final String name;
        final byte[] data;
        boolean encrypted;
        boolean corruptCrc;

        Entry(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }

        Entry encrypted() {
            this.encrypted = true;
            return this;
        }

        Entry corruptCrc() {
            this.corruptCrc = true;
            return this;
        }
    }

    static Entry entry(String name, String content) {
        return new Entry(name, content.getBytes(StandardCharsets.UTF_8));
    }

    static Entry entry(String name, byte[] content) {
        return new Entry(name, content);
    }

    static Path write(Path dir, String fileName, List<Entry> entries) throws IOException {
        Path target = dir.resolve(fileName);
        Files.write(target, build(entries));
        return target;
    }

    static byte[] build(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<long[]> central = new ArrayList<>(); // {nameLen, dataLen, crc, localOffset}
        List<byte[]> names = new ArrayList<>();

        for (Entry entry : entries) {
            byte[] nameBytes = entry.name.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.data);
            long crcValue = entry.corruptCrc ? (crc.getValue() ^ 0xDEADBEEFL) : crc.getValue();
            long localOffset = out.size();

            writeInt(out, 0x04034B50);          // local file header signature
            writeShort(out, 20);                // version needed
            writeShort(out, entry.encrypted ? 1 : 0); // general purpose flags
            writeShort(out, 0);                 // method: STORED
            writeShort(out, 0);                 // mod time
            writeShort(out, 0x21);              // mod date (1996-01-01)
            writeInt(out, crcValue);
            writeInt(out, entry.data.length);   // compressed size
            writeInt(out, entry.data.length);   // uncompressed size
            writeShort(out, nameBytes.length);
            writeShort(out, 0);                 // extra length
            out.write(nameBytes, 0, nameBytes.length);
            out.write(entry.data, 0, entry.data.length);

            central.add(new long[] {nameBytes.length, entry.data.length, crcValue, localOffset});
            names.add(nameBytes);
        }

        long cdOffset = out.size();
        for (int i = 0; i < central.size(); i++) {
            long[] info = central.get(i);
            byte[] nameBytes = names.get(i);
            writeInt(out, 0x02014B50);          // central directory signature
            writeShort(out, 20);                // version made by
            writeShort(out, 20);                // version needed
            Entry entry = entries.get(i);
            writeShort(out, entry.encrypted ? 1 : 0);
            writeShort(out, 0);                 // method
            writeShort(out, 0);                 // mod time
            writeShort(out, 0x21);              // mod date
            writeInt(out, info[2]);             // crc
            writeInt(out, info[1]);             // compressed size
            writeInt(out, info[1]);             // uncompressed size
            writeShort(out, (int) info[0]);     // name length
            writeShort(out, 0);                 // extra length
            writeShort(out, 0);                 // comment length
            writeShort(out, 0);                 // disk number start
            writeShort(out, 0);                 // internal attrs
            writeInt(out, 0);                   // external attrs
            writeInt(out, info[3]);             // local header offset
            out.write(nameBytes, 0, nameBytes.length);
        }
        long cdSize = out.size() - cdOffset;

        writeInt(out, 0x06054B50);              // EOCD signature
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, central.size());
        writeShort(out, central.size());
        writeInt(out, cdSize);
        writeInt(out, cdOffset);
        writeShort(out, 0);                     // comment length
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }
}
