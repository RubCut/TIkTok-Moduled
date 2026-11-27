package dev.moduled.core;

import java.util.List;
import java.util.Map;

/**
 * Минимальная сериализация JSON для снимков, descriptor'ов и каталога.
 * Строки экранируются полностью, поэтому результат всегда проходит
 * {@link JsonParser}.
 */
public final class JsonWriter {
    private JsonWriter() {
    }

    public static String writeKey(String key) {
        return writeString(key);
    }

    public static String writeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return writeString((String) value);
        }
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer
                || value instanceof Double) {
            return value.toString();
        }
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(writeValue(item));
            }
            return sb.append(']').toString();
        }
        if (value instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(writeString(String.valueOf(entry.getKey())))
                        .append(':')
                        .append(writeValue(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass());
    }

    public static String writeString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
