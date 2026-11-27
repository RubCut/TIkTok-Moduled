package dev.moduled.manager.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Сборщик тестовых пакетов модуля v1 (module.json + classes.dex). */
final class TestModulePackage {

    private TestModulePackage() {
    }

    static String manifest(String id, String version, long versionCode, int apiVersion,
                           String entrypoint, String... targetPackages) {
        StringBuilder targets = new StringBuilder("[");
        for (int i = 0; i < targetPackages.length; i++) {
            if (i > 0) {
                targets.append(',');
            }
            targets.append('"').append(targetPackages[i]).append('"');
        }
        targets.append(']');
        return "{\"id\":\"" + id + "\","
                + "\"name\":\"Модуль " + id + "\","
                + "\"description\":\"Тестовый пакет\","
                + "\"version\":\"" + version + "\","
                + "\"versionCode\":" + versionCode + ","
                + "\"apiVersion\":" + apiVersion + ","
                + "\"entrypoint\":\"" + entrypoint + "\","
                + "\"targetPackages\":" + targets + "}";
    }

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

    static File build(File dir, String fileName, String manifestJson, byte[] dex)
            throws IOException {
        File target = new File(dir, fileName);
        try (ZipOutputStream out = new ZipOutputStream(
                java.nio.file.Files.newOutputStream(target.toPath()))) {
            out.putNextEntry(new ZipEntry("module.json"));
            out.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("classes.dex"));
            out.write(dex);
            out.closeEntry();
        }
        return target;
    }

    static File buildValid(File dir, String fileName, String id, String version, long versionCode)
            throws IOException {
        return build(dir, fileName,
                manifest(id, version, versionCode, 1, "dev.moduled.sample.SampleModule",
                        "dev.moduled.demo"),
                validDex());
    }
}
