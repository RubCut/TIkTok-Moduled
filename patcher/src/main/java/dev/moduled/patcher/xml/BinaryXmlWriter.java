package dev.moduled.patcher.xml;

import dev.moduled.core.Result;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Редактор бинарного XML Android (ТЗ §7.2 PAT-08, PAT-09, PAT-10).
 *
 * <p>Позволяет изменить значение атрибута или добавить атрибут/элемент, сохранив
 * всё остальное содержимое документа байт в байт: существующие launcher-компоненты,
 * authorities, providers, placeholders и разрешения остаются нетронутыми.</p>
 *
 * <p>Строки только добавляются в конец string pool, поэтому resource map и все
 * существующие индексы остаются валидными. Документ со styles в string pool
 * отклоняется: в AndroidManifest.xml их не бывает, а частичная поддержка была бы
 * источником молчаливой порчи.</p>
 */
public final class BinaryXmlWriter {

    private static final int CHUNK_XML = 0x0003;
    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_RESOURCE_MAP = 0x0180;
    private static final int CHUNK_START_NAMESPACE = 0x0100;
    private static final int CHUNK_END_NAMESPACE = 0x0101;
    private static final int CHUNK_START_ELEMENT = 0x0102;
    private static final int CHUNK_END_ELEMENT = 0x0103;

    private static final int FLAG_UTF8 = 1 << 8;
    private static final int ATTRIBUTE_SIZE = 20;

    /** Ресурс ID {@code android:name}. */
    public static final int RES_ANDROID_NAME = 0x01010003;
    public static final int RES_ANDROID_EXPORTED = 0x01010010;
    public static final int RES_ANDROID_EXCLUDE_FROM_RECENTS = 0x01010014;
    public static final int RES_ANDROID_TASK_AFFINITY = 0x01010026;
    public static final int RES_ANDROID_THEME = 0x01010000;

    private final List<String> strings = new ArrayList<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private List<Integer> resourceMap = new ArrayList<>();
    private boolean utf8 = true;
    private int stringPoolFlags;
    private byte[] originalBytes;

    private static final class Chunk {
        final int type;
        final int headerSize;
        byte[] body;

        Chunk(int type, int headerSize, byte[] body) {
            this.type = type;
            this.headerSize = headerSize;
            this.body = body;
        }
    }

    private BinaryXmlWriter() {
    }

    /** Читает документ для редактирования. */
    public static Result<BinaryXmlWriter> read(byte[] data) {
        if (data == null || data.length < 8) {
            return Result.fail("AXML_TOO_SMALL", "Бинарный XML меньше минимального заголовка");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int fileType = buffer.getShort(0) & 0xFFFF;
        int fileSize = buffer.getInt(4);
        if (fileType != CHUNK_XML) {
            return Result.fail("AXML_BAD_TYPE", "Файл не является бинарным XML Android");
        }
        if (fileSize > data.length) {
            return Result.fail("AXML_TRUNCATED", "Объявленный размер больше фактического");
        }
        BinaryXmlWriter writer = new BinaryXmlWriter();
        writer.originalBytes = data.clone();

        int position = 8;
        boolean poolSeen = false;
        while (position + 8 <= fileSize) {
            int type = buffer.getShort(position) & 0xFFFF;
            int headerSize = buffer.getShort(position + 2) & 0xFFFF;
            long chunkSize = buffer.getInt(position + 4) & 0xFFFFFFFFL;
            if (chunkSize < headerSize || position + chunkSize > fileSize) {
                return Result.fail("AXML_BAD_CHUNK",
                        "Chunk 0x" + Integer.toHexString(type) + " выходит за границы файла");
            }
            byte[] raw = new byte[(int) chunkSize];
            System.arraycopy(data, position, raw, 0, (int) chunkSize);
            if (type == CHUNK_STRING_POOL) {
                if (poolSeen) {
                    return Result.fail("AXML_TWO_POOLS", "Два string pool в одном документе");
                }
                poolSeen = true;
                Result<Void> parsed = writer.parseStringPool(raw);
                if (!parsed.isOk()) {
                    return Result.fail(parsed.errorCode(), parsed.errorMessage());
                }
                writer.chunks.add(new Chunk(type, headerSize, raw));
            } else if (type == CHUNK_RESOURCE_MAP) {
                Result<List<Integer>> map = parseResourceMap(raw);
                if (!map.isOk()) {
                    return Result.fail(map.errorCode(), map.errorMessage());
                }
                writer.resourceMap = map.value();
                writer.chunks.add(new Chunk(type, headerSize, raw));
            } else {
                writer.chunks.add(new Chunk(type, headerSize, raw));
            }
            position += (int) chunkSize;
        }
        if (!poolSeen) {
            return Result.fail("AXML_NO_STRING_POOL", "В документе нет string pool");
        }
        return Result.ok(writer);
    }

    private Result<Void> parseStringPool(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (raw.length < 28) {
            return Result.fail("AXML_BAD_STRING_POOL", "String pool меньше 28 байт");
        }
        int stringCount = buffer.getInt(8);
        int styleCount = buffer.getInt(12);
        stringPoolFlags = buffer.getInt(16);
        int stringsStart = buffer.getInt(20);
        utf8 = (stringPoolFlags & FLAG_UTF8) != 0;
        if (styleCount != 0) {
            return Result.fail("AXML_STYLES_UNSUPPORTED",
                    "String pool со стилями не поддерживается: найден styleCount=" + styleCount);
        }
        if (stringCount < 0 || stringsStart < 28 || stringsStart > raw.length) {
            return Result.fail("AXML_BAD_STRING_POOL", "Некорректные параметры string pool");
        }
        for (int i = 0; i < stringCount; i++) {
            int offsetPosition = 28 + i * 4;
            if (offsetPosition + 4 > raw.length) {
                return Result.fail("AXML_TRUNCATED", "Обрезаны смещения string pool");
            }
            int offset = buffer.getInt(offsetPosition);
            int absolute = stringsStart + offset;
            if (absolute < stringsStart || absolute > raw.length) {
                return Result.fail("AXML_BAD_STRING_POOL", "Смещение строки " + i + " вне пула");
            }
            Result<String> value = utf8
                    ? readUtf8(buffer, absolute, raw.length)
                    : readUtf16(buffer, absolute, raw.length);
            if (!value.isOk()) {
                return Result.fail(value.errorCode(), value.errorMessage());
            }
            strings.add(value.value());
        }
        return Result.ok(null);
    }

    private static Result<List<Integer>> parseResourceMap(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        List<Integer> map = new ArrayList<>();
        for (int position = 8; position + 4 <= raw.length; position += 4) {
            map.add(buffer.getInt(position));
        }
        return Result.ok(map);
    }

    private static Result<String> readUtf8(ByteBuffer buffer, int position, int limit) {
        int cursor = position;
        if (cursor >= limit) {
            return Result.fail("AXML_TRUNCATED", "Обрезана UTF-8 строка");
        }
        // Первое поле — число символов, второе — число байтов.
        cursor = skipLength(buffer, cursor, limit);
        if (cursor < 0) {
            return Result.fail("AXML_TRUNCATED", "Обрезана длина UTF-8 строки");
        }
        int byteLength = readLength(buffer, cursor, limit);
        if (byteLength < 0) {
            return Result.fail("AXML_TRUNCATED", "Обрезана байтовая длина UTF-8 строки");
        }
        int valueStart = cursor + ((buffer.get(cursor) & 0x80) != 0 ? 2 : 1);
        if (valueStart + byteLength > limit) {
            return Result.fail("AXML_TRUNCATED", "UTF-8 строка выходит за границы пула");
        }
        byte[] bytes = new byte[byteLength];
        for (int i = 0; i < byteLength; i++) {
            bytes[i] = buffer.get(valueStart + i);
        }
        return Result.ok(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Result<String> readUtf16(ByteBuffer buffer, int position, int limit) {
        int charCount = readLength(buffer, position, limit);
        if (charCount < 0) {
            return Result.fail("AXML_TRUNCATED", "Обрезана длина UTF-16 строки");
        }
        int valueStart = position + ((buffer.get(position) & 0x80) != 0 ? 2 : 1);
        if (valueStart + charCount * 2 > limit) {
            return Result.fail("AXML_TRUNCATED", "UTF-16 строка выходит за границы пула");
        }
        char[] chars = new char[charCount];
        for (int i = 0; i < charCount; i++) {
            chars[i] = buffer.getChar(valueStart + i * 2);
        }
        return Result.ok(new String(chars));
    }

    private static int readLength(ByteBuffer buffer, int position, int limit) {
        if (position >= limit) {
            return -1;
        }
        int first = buffer.get(position) & 0xFF;
        if ((first & 0x80) == 0) {
            return first;
        }
        if (position + 1 >= limit) {
            return -1;
        }
        return ((first & 0x7F) << 8) | (buffer.get(position + 1) & 0xFF);
    }

    private static int skipLength(ByteBuffer buffer, int position, int limit) {
        if (position >= limit) {
            return -1;
        }
        return (buffer.get(position) & 0x80) != 0 ? position + 2 : position + 1;
    }

    /** Индекс строки в пуле; при отсутствии строка добавляется в конец. */
    public int internString(String value) {
        int index = strings.indexOf(value);
        if (index >= 0) {
            return index;
        }
        strings.add(value);
        return strings.size() - 1;
    }

    public String stringAt(int index) {
        return index >= 0 && index < strings.size() ? strings.get(index) : null;
    }

    public List<Integer> resourceMap() {
        return Collections.unmodifiableList(resourceMap);
    }

    /**
     * Индекс строки-имени атрибута с указанным resource ID.
     *
     * @return индекс или -1, если атрибут не объявлен в resource map
     */
    public int attributeStringIndex(int resourceId) {
        int index = resourceMap.indexOf(resourceId);
        return index >= 0 && index < strings.size() ? index : -1;
    }

    /** Индекс строки URI пространства имён {@code android}. */
    public int androidNamespaceIndex() {
        int index = strings.indexOf("http://schemas.android.com/apk/res/android");
        if (index >= 0) {
            return index;
        }
        for (String candidate : strings) {
            if (candidate != null && candidate.startsWith("http://schemas.android.com/apk/res")) {
                return strings.indexOf(candidate);
            }
        }
        return internString("http://schemas.android.com/apk/res/android");
    }

    /**
     * Устанавливает строковое значение атрибута у элемента по пути
     * (например {@code manifest/application}). Атрибут добавляется, если его нет.
     *
     * @param resourceId resource ID атрибута из resource map (для {@code android:*})
     * @return старое значение или null
     */
    public Result<String> setStringAttribute(String[] path, String attributeName,
                                             String value, int resourceId) {
        Result<ElementRef> found = findElement(path);
        if (!found.isOk()) {
            return Result.fail(found.errorCode(), found.errorMessage());
        }
        ElementRef reference = found.value();
        int nameIndex = attributeNameIndex(attributeName, resourceId);
        if (nameIndex < 0) {
            return Result.fail("AXML_ATTRIBUTE_NOT_IN_MAP",
                    "Атрибут " + attributeName + " отсутствует в resource map; "
                            + "добавление такого атрибута потребовало бы перестройки пула");
        }
        int valueIndex = internString(value);
        ByteBuffer buffer = ByteBuffer.wrap(reference.chunk.body).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = reference.chunk.headerSize;
        int attributeStart = buffer.getShort(headerSize + 8) & 0xFFFF;
        int attributeCount = buffer.getShort(headerSize + 12) & 0xFFFF;
        int attributesOffset = headerSize + attributeStart;
        String oldValue = null;
        boolean replaced = false;
        for (int i = 0; i < attributeCount; i++) {
            int offset = attributesOffset + i * ATTRIBUTE_SIZE;
            if (buffer.getInt(offset + 4) != nameIndex) {
                continue;
            }
            int dataType = buffer.get(offset + 15) & 0xFF;
            if (dataType == XmlValue.TYPE_STRING) {
                oldValue = stringAt(buffer.getInt(offset + 16));
            }
            buffer.putInt(offset + 8, valueIndex);
            buffer.put(offset + 14, (byte) 0);
            buffer.put(offset + 15, (byte) XmlValue.TYPE_STRING);
            buffer.putInt(offset + 16, valueIndex);
            replaced = true;
            break;
        }
        if (!replaced) {
            int androidNs = androidNamespaceIndex();
            byte[] grown = new byte[reference.chunk.body.length + ATTRIBUTE_SIZE];
            System.arraycopy(reference.chunk.body, 0, grown, 0, reference.chunk.body.length);
            ByteBuffer grownBuffer = ByteBuffer.wrap(grown).order(ByteOrder.LITTLE_ENDIAN);
            grownBuffer.putInt(attributesOffset + attributeCount * ATTRIBUTE_SIZE, androidNs);
            grownBuffer.putInt(attributesOffset + attributeCount * ATTRIBUTE_SIZE + 4, nameIndex);
            grownBuffer.putInt(attributesOffset + attributeCount * ATTRIBUTE_SIZE + 8, valueIndex);
            grownBuffer.putShort(attributesOffset + attributeCount * ATTRIBUTE_SIZE + 12,
                    (short) 8);
            grownBuffer.put(attributesOffset + attributeCount * ATTRIBUTE_SIZE + 14, (byte) 0);
            grownBuffer.put(attributesOffset + attributeCount * ATTRIBUTE_SIZE + 15,
                    (byte) XmlValue.TYPE_STRING);
            grownBuffer.putInt(attributesOffset + attributeCount * ATTRIBUTE_SIZE + 16, valueIndex);
            grownBuffer.putInt(4, grown.length);
            grownBuffer.putShort(headerSize + 12, (short) (attributeCount + 1));
            // classIndex/idIndex указывают на 1-based индекс атрибута: для
            // <application> android:name обязан быть в classIndex.
            if (resourceId == RES_ANDROID_NAME && reference.chunkIsApplication) {
                grownBuffer.putShort(headerSize + 16, (short) (attributeCount + 1));
            }
            reference.chunk.body = grown;
        }
        return Result.ok(oldValue);
    }

    private int attributeNameIndex(String attributeName, int resourceId) {
        int fromMap = attributeStringIndex(resourceId);
        if (fromMap >= 0) {
            return fromMap;
        }
        return strings.indexOf(attributeName);
    }

    /** Добавляет дочерний элемент с указанными строковыми атрибутами. */
    public Result<Void> addElement(String[] parentPath, String elementName,
                                   String[][] attributes) {
        Result<ElementRef> found = findElement(parentPath);
        if (!found.isOk()) {
            return Result.fail(found.errorCode(), found.errorMessage());
        }
        int parentChunkIndex = found.value().chunkIndex;
        int androidNs = androidNamespaceIndex();

        // Android-атрибут добавляется только если его имя уже есть в resource map:
        // иначе платформа не сопоставит атрибут с resource ID и молча
        // проигнорирует его. Обязательный атрибут (name) при отсутствии в карте —
        // ошибка; необязательные пропускаются, и вызывающий код это видит.
        List<int[]> usable = new ArrayList<>();
        for (String[] attribute : attributes) {
            String attrName = attribute[0];
            int resourceId = resourceIdOf(attrName);
            int mappedIndex = resourceId > 0 ? attributeStringIndex(resourceId) : -1;
            if (resourceId > 0 && mappedIndex < 0) {
                if (resourceId == RES_ANDROID_NAME) {
                    return Result.fail("AXML_ATTRIBUTE_NOT_IN_MAP",
                            "Атрибут android:name отсутствует в resource map документа: "
                                    + "добавление элемента невозможно без перестройки пула строк");
                }
                continue;
            }
            int nameIdx = mappedIndex >= 0 ? mappedIndex : internString(attrName);
            int valueIdx = internString(attribute[1]);
            usable.add(new int[] {nameIdx, valueIdx});
        }
        int nameIndex = internString(elementName);
        int count = usable.size();
        int[] attributeNameIndexes = new int[count];
        int[] valueIndexes = new int[count];
        for (int i = 0; i < count; i++) {
            attributeNameIndexes[i] = usable.get(i)[0];
            valueIndexes[i] = usable.get(i)[1];
        }

        // Start element chunk: header(16) + ns(4) + name(4) + attributeStart(2)
        // + attributeSize(2) + attributeCount(2) + idIndex(2) + classIndex(2)
        // + styleIndex(2) + count * 20.
        int attributeBytes = ATTRIBUTE_SIZE * count;
        int startSize = 36 + attributeBytes;
        ByteBuffer start = ByteBuffer.allocate(startSize).order(ByteOrder.LITTLE_ENDIAN);
        start.putShort((short) CHUNK_START_ELEMENT);
        start.putShort((short) 16);
        start.putInt(startSize);
        start.putInt(0);          // lineNumber
        start.putInt(-1);         // comment
        start.putInt(-1);         // ns элемента отсутствует
        start.putInt(nameIndex);
        start.putShort((short) 20);            // attributeStart
        start.putShort((short) ATTRIBUTE_SIZE);// attributeSize
        start.putShort((short) count);
        start.putShort((short) 0);             // idIndex
        int nameResourceIndex = attributeStringIndex(RES_ANDROID_NAME);
        short classIndex = 0;
        for (int i = 0; i < count; i++) {
            if (attributeNameIndexes[i] == nameResourceIndex && nameResourceIndex >= 0) {
                classIndex = (short) (i + 1);
            }
        }
        start.putShort(classIndex);
        start.putShort((short) 0);             // styleIndex
        for (int i = 0; i < count; i++) {
            start.putInt(androidNs);
            start.putInt(attributeNameIndexes[i]);
            start.putInt(valueIndexes[i]);
            start.putShort((short) 8);         // размер typed value
            start.put((byte) 0);               // res0
            start.put((byte) XmlValue.TYPE_STRING);
            start.putInt(valueIndexes[i]);     // data = индекс строки
        }

        ByteBuffer end = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        end.putShort((short) CHUNK_END_ELEMENT);
        end.putShort((short) 16);
        end.putInt(24);
        end.putInt(0);
        end.putInt(-1);
        end.putInt(-1);
        end.putInt(nameIndex);

        // Вставка перед закрывающим chunk родителя: новый элемент становится
        // ПОСЛЕДНИМ дочерним, поэтому позиции существующих одноимённых детей
        // (activity, provider) не сдвигаются — проверка неизменности manifest
        // после правки остаётся корректной (PAT-10).
        int closingIndex = findClosingChunk(parentChunkIndex);
        if (closingIndex < 0) {
            return Result.fail("AXML_UNBALANCED",
                    "Для элемента " + String.join("/", parentPath) + " не найден закрывающий chunk");
        }
        chunks.add(closingIndex, new Chunk(CHUNK_END_ELEMENT, 16, end.array()));
        chunks.add(closingIndex, new Chunk(CHUNK_START_ELEMENT, 16, start.array()));
        return Result.ok(null);
    }

    /** Индекс закрывающего chunk, парного открывающему chunk с индексом {@code openIndex}. */
    private int findClosingChunk(int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < chunks.size(); i++) {
            int type = chunks.get(i).type;
            if (type == CHUNK_START_ELEMENT) {
                depth++;
            } else if (type == CHUNK_END_ELEMENT) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int resourceIdOf(String attributeName) {
        switch (attributeName) {
            case "name": return RES_ANDROID_NAME;
            case "exported": return RES_ANDROID_EXPORTED;
            case "excludeFromRecents": return RES_ANDROID_EXCLUDE_FROM_RECENTS;
            case "taskAffinity": return RES_ANDROID_TASK_AFFINITY;
            case "theme": return RES_ANDROID_THEME;
            default: return -1;
        }
    }

    private static void writeChunkHeader(ByteArrayOutputStream out, int type, int headerSize,
                                         int payloadSize) {
        writeShort(out, type);
        writeShort(out, headerSize);
        writeInt(out, headerSize + payloadSize);
        writeInt(out, 0);   // lineNumber неизвестен: 0 допустимо
        writeInt(out, -1);  // comment отсутствует
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

    private static final class ElementRef {
        final Chunk chunk;
        final int chunkIndex;
        final boolean chunkIsApplication;

        ElementRef(Chunk chunk, int chunkIndex, boolean chunkIsApplication) {
            this.chunk = chunk;
            this.chunkIndex = chunkIndex;
            this.chunkIsApplication = chunkIsApplication;
        }
    }

    private Result<ElementRef> findElement(String[] path) {
        List<String> stack = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (chunk.type == CHUNK_START_ELEMENT) {
                ByteBuffer buffer = ByteBuffer.wrap(chunk.body).order(ByteOrder.LITTLE_ENDIAN);
                int nameIndex = buffer.getInt(chunk.headerSize + 4);
                String name = stringAt(nameIndex);
                stack.add(name != null ? name : "");
                if (stack.size() == path.length && matchesPath(stack, path)) {
                    return Result.ok(new ElementRef(chunk, i,
                            path.length > 0 && "application".equals(path[path.length - 1])));
                }
            } else if (chunk.type == CHUNK_END_ELEMENT) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            }
        }
        return Result.fail("AXML_PATH_NOT_FOUND",
                "Элемент не найден по пути " + String.join("/", path));
    }

    private static boolean matchesPath(List<String> stack, String[] path) {
        for (int i = 0; i < path.length; i++) {
            if (!path[i].equals(stack.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Собирает итоговый документ: новый string pool + изменённые chunk. */
    public byte[] write() {
        byte[] pool = buildStringPool();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = 8 + pool.length;
        List<byte[]> serialized = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (chunk.type == CHUNK_STRING_POOL) {
                continue;
            }
            if (chunk.type == CHUNK_RESOURCE_MAP) {
                serialized.add(chunk.body);
                total += chunk.body.length;
                continue;
            }
            serialized.add(chunk.body);
            total += chunk.body.length;
        }
        writeShort(out, CHUNK_XML);
        writeShort(out, 8);
        writeInt(out, total);
        out.write(pool, 0, pool.length);
        for (byte[] chunk : serialized) {
            out.write(chunk, 0, chunk.length);
        }
        return out.toByteArray();
    }

    private byte[] buildStringPool() {
        List<byte[]> encoded = new ArrayList<>(strings.size());
        int dataSize = 0;
        for (String value : strings) {
            byte[] bytes = utf8
                    ? value.getBytes(StandardCharsets.UTF_8)
                    : null;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (utf8) {
                writeLengthPrefix(body, value.length());
                writeLengthPrefix(body, bytes.length);
                body.write(bytes, 0, bytes.length);
                body.write(0);
            } else {
                writeLengthPrefix(body, value.length());
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    body.write(c & 0xFF);
                    body.write((c >> 8) & 0xFF);
                }
                body.write(0);
                body.write(0);
            }
            byte[] encodedString = body.toByteArray();
            encoded.add(encodedString);
            dataSize += encodedString.length;
        }
        int offsetsSize = strings.size() * 4;
        int headerSize = 28;
        int stringsStart = headerSize + offsetsSize;
        int chunkSize = stringsStart + dataSize;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, CHUNK_STRING_POOL);
        writeShort(out, headerSize);
        writeInt(out, chunkSize);
        writeInt(out, strings.size());
        writeInt(out, 0);                 // styleCount
        writeInt(out, stringPoolFlags);
        writeInt(out, stringsStart);
        writeInt(out, 0);                 // stylesStart
        int offset = 0;
        for (byte[] encodedString : encoded) {
            writeInt(out, offset);
            offset += encodedString.length;
        }
        for (byte[] encodedString : encoded) {
            out.write(encodedString, 0, encodedString.length);
        }
        return out.toByteArray();
    }

    private static void writeLengthPrefix(ByteArrayOutputStream out, int length) {
        if (length > 0x7F) {
            out.write(((length >> 8) & 0x7F) | 0x80);
            out.write(length & 0xFF);
        } else {
            out.write(length);
        }
    }

    /** Исходные байты до редактирования: нужны для diff-отчёта (§15.2). */
    public byte[] originalBytes() {
        return originalBytes != null ? originalBytes.clone() : new byte[0];
    }
}
