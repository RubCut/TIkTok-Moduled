package dev.moduled.patcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Генератор минимального валидного бинарного AndroidManifest.xml (AXML) для
 * JVM-тестов патчера: string pool UTF-8, resource map, namespace android,
 * вложенные элементы с атрибутами.
 */
final class TestAxml {

    private static final int CHUNK_XML = 0x0003;
    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_RESOURCE_MAP = 0x0180;
    private static final int CHUNK_START_NS = 0x0100;
    private static final int CHUNK_END_NS = 0x0101;
    private static final int CHUNK_START_ELEMENT = 0x0102;
    private static final int CHUNK_END_ELEMENT = 0x0103;
    private static final int FLAG_UTF8 = 1 << 8;

    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** Имена android-атрибутов и их resource ID (порядок = порядок в resource map). */
    private static final String[][] ATTRIBUTE_RESOURCES = {
            {"package", "0x0101021b"},
            {"versionCode", "0x0101021c"},
            {"versionName", "0x0101021d"},
            {"name", "0x01010003"},
            {"authorities", "0x01010018"},
            {"exported", "0x01010010"},
            {"excludeFromRecents", "0x01010014"},
            {"taskAffinity", "0x01010026"},
            {"process", "0x01010011"},
            {"minSdkVersion", "0x0101020c"},
            {"targetSdkVersion", "0x01010270"},
    };

    private final List<String> strings = new ArrayList<>();
    private final Map<String, Integer> resourceIds = new LinkedHashMap<>();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    private TestAxml() {
        for (String[] entry : ATTRIBUTE_RESOURCES) {
            resourceIds.put(entry[0], Integer.decode(entry[1]));
        }
    }

    /**
     * Собирает manifest: {@code <manifest package=... versionCode=... versionName=...>}
     * с {@code <uses-sdk>}, {@code <application android:name=...>} и вложенными
     * provider/activity/intent-filter.
     */
    static byte[] manifest(String packageName, long versionCode, String versionName,
                           String applicationName, String providerAuthorities,
                           String launcherActivity, int minSdk, int targetSdk) {
        TestAxml axml = new TestAxml();
        // Строки пула: сначала имена атрибутов (resource map), затем остальные.
        List<String> ordered = new ArrayList<>();
        for (String[] entry : ATTRIBUTE_RESOURCES) {
            ordered.add(entry[0]);
        }
        ordered.add(ANDROID_NS);
        ordered.add("manifest");
        ordered.add(packageName);
        ordered.add(versionName);
        ordered.add("uses-sdk");
        ordered.add("application");
        if (applicationName != null) {
            ordered.add(applicationName);
        }
        ordered.add("provider");
        if (providerAuthorities != null) {
            ordered.add(providerAuthorities);
        }
        ordered.add("activity");
        if (launcherActivity != null) {
            ordered.add(launcherActivity);
            ordered.add("intent-filter");
            ordered.add("action");
            ordered.add("android.intent.action.MAIN");
            ordered.add("category");
            ordered.add("android.intent.category.LAUNCHER");
        }
        ordered.add("true");
        ordered.add("false");
        for (String value : ordered) {
            axml.intern(value);
        }

        axml.startElement("manifest", new Object[][] {
                {"package", packageName, 3},
                {"versionCode", Long.valueOf(versionCode), 0x10},
                {"versionName", versionName, 3},
        });
        axml.startElement("uses-sdk", new Object[][] {
                {"minSdkVersion", Long.valueOf(minSdk), 0x10},
                {"targetSdkVersion", Long.valueOf(targetSdk), 0x10},
        });
        axml.endElement("uses-sdk");
        axml.startElement("application", applicationName != null
                ? new Object[][] {{"name", applicationName, 3}}
                : new Object[0][]);
        axml.startElement("provider", new Object[][] {
                {"name", "com.example.host.InternalProvider", 3},
                {"authorities", providerAuthorities, 3},
                {"exported", "false", 3},
        });
        axml.endElement("provider");
        if (launcherActivity != null) {
            axml.startElement("activity", new Object[][] {
                    {"name", launcherActivity, 3},
                    {"exported", "true", 3},
            });
            axml.startElement("intent-filter", new Object[0][]);
            axml.startElement("action", new Object[][] {
                    {"name", "android.intent.action.MAIN", 3}});
            axml.endElement("action");
            axml.startElement("category", new Object[][] {
                    {"name", "android.intent.category.LAUNCHER", 3}});
            axml.endElement("category");
            axml.endElement("intent-filter");
            axml.endElement("activity");
        }
        axml.endElement("application");
        axml.endElement("manifest");
        return axml.build();
    }

    private int intern(String value) {
        int index = strings.indexOf(value);
        if (index < 0) {
            strings.add(value);
            return strings.size() - 1;
        }
        return index;
    }

    private void startElement(String name, Object[][] attributes) {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        int attributeCount = attributes.length;
        int size = 36 + 20 * attributeCount;
        writeShort(chunk, CHUNK_START_ELEMENT);
        writeShort(chunk, 16);
        writeInt(chunk, size);
        writeInt(chunk, 1);      // lineNumber
        writeInt(chunk, -1);     // comment
        writeInt(chunk, -1);     // ns элемента
        writeInt(chunk, intern(name));
        writeShort(chunk, 20);   // attributeStart
        writeShort(chunk, 20);   // attributeSize
        writeShort(chunk, attributeCount);
        writeShort(chunk, 0);    // idIndex
        writeShort(chunk, 0);    // classIndex
        writeShort(chunk, 0);    // styleIndex
        for (Object[] attribute : attributes) {
            String attributeName = (String) attribute[0];
            Object value = attribute[1];
            int dataType = (Integer) attribute[2];
            writeInt(chunk, intern(ANDROID_NS));
            writeInt(chunk, intern(attributeName));
            if (dataType == 3) {
                int valueIndex = intern(String.valueOf(value));
                writeInt(chunk, valueIndex);
                writeShort(chunk, 8);
                chunk.write(0);
                chunk.write(3);
                writeInt(chunk, valueIndex);
            } else {
                writeInt(chunk, -1);
                writeShort(chunk, 8);
                chunk.write(0);
                chunk.write(dataType);
                writeInt(chunk, ((Number) value).intValue());
            }
        }
        body.write(chunk.toByteArray(), 0, chunk.size());
    }

    private void endElement(String name) {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        writeShort(chunk, CHUNK_END_ELEMENT);
        writeShort(chunk, 16);
        writeInt(chunk, 24);
        writeInt(chunk, 1);
        writeInt(chunk, -1);
        writeInt(chunk, -1);
        writeInt(chunk, intern(name));
        body.write(chunk.toByteArray(), 0, chunk.size());
    }

    private byte[] build() {
        byte[] pool = buildStringPool();
        byte[] resourceMap = buildResourceMap();
        byte[] startNs = buildNamespace(CHUNK_START_NS);
        byte[] endNs = buildNamespace(CHUNK_END_NS);
        byte[] elements = body.toByteArray();
        int fileSize = 8 + pool.length + resourceMap.length + startNs.length
                + elements.length + endNs.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, CHUNK_XML);
        writeShort(out, 8);
        writeInt(out, fileSize);
        out.write(pool, 0, pool.length);
        out.write(resourceMap, 0, resourceMap.length);
        out.write(startNs, 0, startNs.length);
        out.write(elements, 0, elements.length);
        out.write(endNs, 0, endNs.length);
        return out.toByteArray();
    }

    private byte[] buildResourceMap() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, CHUNK_RESOURCE_MAP);
        writeShort(out, 8);
        writeInt(out, 8 + 4 * ATTRIBUTE_RESOURCES.length);
        for (String[] entry : ATTRIBUTE_RESOURCES) {
            writeInt(out, resourceIds.get(entry[0]));
        }
        return out.toByteArray();
    }

    private byte[] buildNamespace(int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, type);
        writeShort(out, 16);
        writeInt(out, 24);
        writeInt(out, 1);
        writeInt(out, -1);
        writeInt(out, intern("android"));
        writeInt(out, intern(ANDROID_NS));
        return out.toByteArray();
    }

    private byte[] buildStringPool() {
        List<byte[]> encoded = new ArrayList<>();
        int dataSize = 0;
        for (String value : strings) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            writeLength(entry, value.length());
            writeLength(entry, bytes.length);
            entry.write(bytes, 0, bytes.length);
            entry.write(0);
            byte[] encodedString = entry.toByteArray();
            encoded.add(encodedString);
            dataSize += encodedString.length;
        }
        int offsetsSize = strings.size() * 4;
        int stringsStart = 28 + offsetsSize;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, CHUNK_STRING_POOL);
        writeShort(out, 28);
        writeInt(out, stringsStart + dataSize);
        writeInt(out, strings.size());
        writeInt(out, 0);            // styleCount
        writeInt(out, FLAG_UTF8);
        writeInt(out, stringsStart);
        writeInt(out, 0);            // stylesStart
        int offset = 0;
        for (byte[] entry : encoded) {
            writeInt(out, offset);
            offset += entry.length;
        }
        for (byte[] entry : encoded) {
            out.write(entry, 0, entry.length);
        }
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length > 0x7F) {
            out.write(((length >> 8) & 0x7F) | 0x80);
            out.write(length & 0xFF);
        } else {
            out.write(length);
        }
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
