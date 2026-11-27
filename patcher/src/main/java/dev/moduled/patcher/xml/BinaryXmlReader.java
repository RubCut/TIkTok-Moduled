package dev.moduled.patcher.xml;

import dev.moduled.core.Result;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Читатель бинарного XML Android (AXML), в котором хранится
 * {@code AndroidManifest.xml} внутри APK (ТЗ §7.2 PAT-01).
 *
 * <p>Поддерживаются: string pool (UTF-8 и UTF-16), карта ресурсов, пространства
 * имён, элементы с атрибутами всех стандартных типов. Файл читается по границам
 * chunks с проверкой размеров: повреждённый или обрезанный manifest отклоняется
 * понятной причиной, а не интерпретируется частично.</p>
 */
public final class BinaryXmlReader {

    private static final int CHUNK_XML = 0x0003;
    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_RESOURCE_MAP = 0x0180;
    private static final int CHUNK_START_NAMESPACE = 0x0100;
    private static final int CHUNK_END_NAMESPACE = 0x0101;
    private static final int CHUNK_START_ELEMENT = 0x0102;
    private static final int CHUNK_END_ELEMENT = 0x0103;
    private static final int CHUNK_CDATA = 0x0104;

    private static final int FLAG_UTF8 = 1 << 8;
    private static final int MAX_CHUNKS = 100_000;

    private BinaryXmlReader() {
    }

    public static Result<XmlDocument> parse(byte[] data) {
        if (data == null || data.length < 8) {
            return Result.fail("AXML_TOO_SMALL", "Бинарный XML меньше минимального заголовка");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int fileType = buffer.getShort(0) & 0xFFFF;
        int fileSize = buffer.getInt(4);
        if (fileType != CHUNK_XML) {
            return Result.fail("AXML_BAD_TYPE",
                    "Это не бинарный XML Android (type=0x" + Integer.toHexString(fileType) + ')');
        }
        if (fileSize > data.length) {
            return Result.fail("AXML_TRUNCATED",
                    "Объявленный размер " + fileSize + " больше фактического " + data.length);
        }

        List<String> strings = new ArrayList<>();
        List<XmlElement> stack = new ArrayList<>();
        XmlElement root = null;
        XmlDocument document = null;
        int position = 8;
        int chunks = 0;

        while (position + 8 <= fileSize) {
            if (++chunks > MAX_CHUNKS) {
                return Result.fail("AXML_TOO_MANY_CHUNKS", "Слишком много chunks в бинарном XML");
            }
            int type = buffer.getShort(position) & 0xFFFF;
            int headerSize = buffer.getShort(position + 2) & 0xFFFF;
            long chunkSize = buffer.getInt(position + 4) & 0xFFFFFFFFL;
            if (chunkSize < headerSize || headerSize < 8) {
                return Result.fail("AXML_BAD_CHUNK",
                        "Некорректные размеры chunk: header=" + headerSize + " size=" + chunkSize);
            }
            if (position + chunkSize > fileSize) {
                return Result.fail("AXML_TRUNCATED",
                        "Chunk типа 0x" + Integer.toHexString(type) + " выходит за границы файла");
            }
            switch (type) {
                case CHUNK_STRING_POOL:
                    Result<List<String>> parsedPool = parseStringPool(buffer, position, (int) chunkSize);
                    if (!parsedPool.isOk()) {
                        return Result.fail(parsedPool.errorCode(), parsedPool.errorMessage());
                    }
                    strings = parsedPool.value();
                    break;
                case CHUNK_RESOURCE_MAP:
                    // Карта ресурсов не нужна для анализа: имена атрибутов берутся из string pool.
                    break;
                case CHUNK_START_NAMESPACE: {
                    if (headerSize + 8 > chunkSize) {
                        return Result.fail("AXML_BAD_NAMESPACE", "Обрезан chunk пространства имён");
                    }
                    int prefixIndex = buffer.getInt(position + headerSize);
                    int uriIndex = buffer.getInt(position + headerSize + 4);
                    if (document == null) {
                        document = new XmlDocument(null);
                    }
                    document.namespaces.add(new String[] {
                            stringAt(strings, prefixIndex), stringAt(strings, uriIndex)});
                    break;
                }
                case CHUNK_END_NAMESPACE:
                    break;
                case CHUNK_START_ELEMENT: {
                    Result<XmlElement> parsedElement =
                            parseElement(buffer, position, headerSize, strings);
                    if (!parsedElement.isOk()) {
                        return Result.fail(parsedElement.errorCode(), parsedElement.errorMessage());
                    }
                    XmlElement element = parsedElement.value();
                    if (stack.isEmpty()) {
                        root = element;
                        document = new XmlDocument(root);
                    } else {
                        XmlElement parent = stack.get(stack.size() - 1);
                        element.parent = parent;
                        parent.children.add(element);
                    }
                    stack.add(element);
                    break;
                }
                case CHUNK_END_ELEMENT:
                    if (stack.isEmpty()) {
                        return Result.fail("AXML_UNBALANCED", "EndElement без открытого элемента");
                    }
                    stack.remove(stack.size() - 1);
                    break;
                case CHUNK_CDATA:
                    break;
                default:
                    // Неизвестный chunk пропускается по его размеру: формат расширяем.
                    break;
            }
            position += (int) chunkSize;
        }

        if (root == null) {
            return Result.fail("AXML_NO_ROOT", "В бинарном XML нет корневого элемента");
        }
        if (!stack.isEmpty()) {
            return Result.fail("AXML_UNBALANCED",
                    "Не закрыты элементы: " + stack.size() + " (повреждённый manifest)");
        }
        return Result.ok(document != null ? document : new XmlDocument(root));
    }

    private static Result<XmlElement> parseElement(ByteBuffer buffer, int chunkStart,
                                                   int headerSize, List<String> strings) {
        // Заголовок элемента: type, headerSize, size, lineNumber, comment,
        // затем ns, name, attributeStart, attributeSize, attributeCount, ...
        if (headerSize < 16) {
            return Result.fail("AXML_BAD_ELEMENT", "Заголовок элемента меньше 16 байт");
        }
        int nameIndex = buffer.getInt(chunkStart + headerSize + 4);
        int attributeStart = buffer.getShort(chunkStart + headerSize + 8) & 0xFFFF;
        int attributeSize = buffer.getShort(chunkStart + headerSize + 10) & 0xFFFF;
        int attributeCount = buffer.getShort(chunkStart + headerSize + 12) & 0xFFFF;
        String name = stringAt(strings, nameIndex);
        if (name == null || name.isEmpty()) {
            return Result.fail("AXML_BAD_ELEMENT", "Имя элемента отсутствует в string pool");
        }
        if (attributeSize != 0 && attributeSize != 20) {
            return Result.fail("AXML_BAD_ELEMENT",
                    "Неожиданный размер атрибута: " + attributeSize + " (ожидалось 20)");
        }
        Map<String, XmlValue> attributes = new LinkedHashMap<>();
        int attributesOffset = chunkStart + headerSize + attributeStart;
        int effectiveAttributeSize = attributeSize != 0 ? attributeSize : 20;
        for (int i = 0; i < attributeCount; i++) {
            int offset = attributesOffset + i * effectiveAttributeSize;
            if (offset + 20 > buffer.limit()) {
                return Result.fail("AXML_TRUNCATED", "Атрибут " + i + " выходит за границы файла");
            }
            int attrNameIndex = buffer.getInt(offset + 4);
            int rawValueIndex = buffer.getInt(offset + 8);
            int dataType = buffer.get(offset + 15) & 0xFF;
            long data = buffer.getInt(offset + 16) & 0xFFFFFFFFL;
            String attrName = stringAt(strings, attrNameIndex);
            if (attrName == null || attrName.isEmpty()) {
                attrName = stringAt(strings, rawValueIndex);
            }
            if (attrName == null || attrName.isEmpty()) {
                attrName = "attribute_" + i;
            }
            XmlValue value;
            switch (dataType) {
                case XmlValue.TYPE_STRING:
                    value = XmlValue.string(stringAt(strings, (int) data));
                    break;
                case XmlValue.TYPE_INT_BOOLEAN:
                    value = XmlValue.bool(data != 0);
                    break;
                case XmlValue.TYPE_INT_DEC:
                case XmlValue.TYPE_INT_HEX:
                case XmlValue.TYPE_REFERENCE:
                    value = new XmlValue(dataType, null, data);
                    break;
                default:
                    value = new XmlValue(dataType, stringAt(strings, (int) data), data);
                    break;
            }
            attributes.put(attrName, value);
        }
        return Result.ok(new XmlElement(name, attributes));
    }

    private static Result<List<String>> parseStringPool(ByteBuffer buffer, int chunkStart,
                                                        int chunkSize) {
        if (chunkSize < 28) {
            return Result.fail("AXML_BAD_STRING_POOL", "String pool меньше 28 байт");
        }
        int stringCount = buffer.getInt(chunkStart + 8);
        int flags = buffer.getInt(chunkStart + 16);
        int stringsStart = buffer.getInt(chunkStart + 20);
        if (stringCount < 0 || stringCount > MAX_CHUNKS) {
            return Result.fail("AXML_BAD_STRING_POOL",
                    "Недопустимое число строк в пуле: " + stringCount);
        }
        if (stringsStart < 28 || stringsStart > chunkSize) {
            return Result.fail("AXML_BAD_STRING_POOL",
                    "Смещение данных строк вне chunk: " + stringsStart);
        }
        boolean utf8 = (flags & FLAG_UTF8) != 0;
        List<String> strings = new ArrayList<>(stringCount);
        int dataStart = chunkStart + stringsStart;
        for (int i = 0; i < stringCount; i++) {
            int offsetPosition = chunkStart + 28 + i * 4;
            if (offsetPosition + 4 > buffer.limit()) {
                return Result.fail("AXML_TRUNCATED", "Обрезаны смещения string pool");
            }
            int offset = buffer.getInt(offsetPosition);
            int absolute = dataStart + offset;
            if (absolute < dataStart || absolute > chunkStart + chunkSize) {
                return Result.fail("AXML_BAD_STRING_POOL",
                        "Смещение строки " + i + " вне пула: " + offset);
            }
            Result<String> parsed = utf8
                    ? readUtf8String(buffer, absolute, chunkStart + chunkSize)
                    : readUtf16String(buffer, absolute, chunkStart + chunkSize);
            if (!parsed.isOk()) {
                return Result.fail(parsed.errorCode(), parsed.errorMessage());
            }
            strings.add(parsed.value());
        }
        return Result.ok(strings);
    }

    private static Result<String> readUtf8String(ByteBuffer buffer, int position, int limit) {
        int cursor = position;
        // Два поля длины: число символов и число байтов, каждое 1 или 2 байта.
        cursor = skipLengthField(buffer, cursor, limit);
        if (cursor < 0) {
            return Result.fail("AXML_TRUNCATED", "Обрезана длина UTF-8 строки");
        }
        LengthField byteLength = readLengthField(buffer, cursor, limit);
        if (byteLength.value < 0) {
            return Result.fail("AXML_TRUNCATED", "Обрезана байтовая длина UTF-8 строки");
        }
        cursor = byteLength.consumed;
        if (cursor + byteLength.value > limit) {
            return Result.fail("AXML_TRUNCATED", "UTF-8 строка выходит за границы пула");
        }
        byte[] bytes = new byte[byteLength.value];
        for (int i = 0; i < byteLength.value; i++) {
            bytes[i] = buffer.get(cursor + i);
        }
        return Result.ok(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Result<String> readUtf16String(ByteBuffer buffer, int position, int limit) {
        LengthField length = readLengthField(buffer, position, limit);
        if (length.value < 0) {
            return Result.fail("AXML_TRUNCATED", "Обрезана длина UTF-16 строки");
        }
        int charCount = length.value;
        int cursor = length.consumed;
        if (cursor + charCount * 2 > limit) {
            return Result.fail("AXML_TRUNCATED", "UTF-16 строка выходит за границы пула");
        }
        char[] chars = new char[charCount];
        for (int i = 0; i < charCount; i++) {
            chars[i] = buffer.getChar(cursor + i * 2);
        }
        return Result.ok(new String(chars));
    }

    private static final class LengthField {
        final int value;
        final int consumed;

        LengthField(int value, int consumed) {
            this.value = value;
            this.consumed = consumed;
        }
    }

    private static LengthField readLengthField(ByteBuffer buffer, int position, int limit) {
        if (position >= limit) {
            return new LengthField(-1, position);
        }
        int first = buffer.get(position) & 0xFF;
        if ((first & 0x80) == 0) {
            return new LengthField(first, position + 1);
        }
        if (position + 1 >= limit) {
            return new LengthField(-1, position);
        }
        int second = buffer.get(position + 1) & 0xFF;
        return new LengthField(((first & 0x7F) << 8) | second, position + 2);
    }

    private static int skipLengthField(ByteBuffer buffer, int position, int limit) {
        LengthField field = readLengthField(buffer, position, limit);
        return field.value < 0 ? -1 : field.consumed;
    }

    private static String stringAt(List<String> strings, int index) {
        if (index < 0 || index >= strings.size()) {
            return null;
        }
        return strings.get(index);
    }
}
