package dev.moduled.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный строгий JSON-парсер без внешних зависимостей.
 * Отличается от lenient-парсеров тем, что:
 * <ul>
 *   <li>отклоняет дублирующиеся ключи объекта (ТЗ §10.3 PKG-04);</li>
 *   <li>отклоняет trailing garbage после корневого значения;</li>
 *   <li>различает целые и дробные числа (нужно для versionCode/apiVersion).</li>
 * </ul>
 * Возвращаемые типы: {@code Map<String,Object>}, {@code List<Object>}, {@code String},
 * {@code Long}, {@code Double}, {@code Boolean}, {@code null}.
 */
public final class JsonParser {
    private final String input;
    private int pos;

    private JsonParser(String input) {
        this.input = input;
    }

    public static Object parse(String input) {
        if (input == null) {
            throw new JsonException("null input");
        }
        JsonParser parser = new JsonParser(input);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos != parser.input.length()) {
            throw new JsonException("Trailing characters at offset " + parser.pos);
        }
        return value;
    }

    private Object parseValue() {
        if (pos >= input.length()) {
            throw new JsonException("Unexpected end of input");
        }
        char c = input.charAt(pos);
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                return parseNumber();
        }
    }

    private Map<String, Object> parseObject() {
        expectChar('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) != '"') {
                throw new JsonException("Expected string key at offset " + pos);
            }
            String key = parseString();
            if (map.containsKey(key)) {
                throw new JsonException("Duplicate key: " + key);
            }
            skipWhitespace();
            expectChar(':');
            skipWhitespace();
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            if (peek(',')) {
                pos++;
                continue;
            }
            if (peek('}')) {
                pos++;
                return map;
            }
            throw new JsonException("Expected ',' or '}' at offset " + pos);
        }
    }

    private List<Object> parseArray() {
        expectChar('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();
            if (peek(',')) {
                pos++;
                continue;
            }
            if (peek(']')) {
                pos++;
                return list;
            }
            throw new JsonException("Expected ',' or ']' at offset " + pos);
        }
    }

    private String parseString() {
        expectChar('"');
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= input.length()) {
                    throw new JsonException("Unterminated escape");
                }
                char e = input.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        if (pos + 4 > input.length()) {
                            throw new JsonException("Bad \\u escape");
                        }
                        String hex = input.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("Bad \\u escape: " + hex);
                        }
                        break;
                    }
                    default:
                        throw new JsonException("Bad escape: \\" + e);
                }
            } else if (c < 0x20) {
                throw new JsonException("Control character in string at offset " + (pos - 1));
            } else {
                sb.append(c);
            }
        }
        throw new JsonException("Unterminated string");
    }

    private Object parseNumber() {
        int start = pos;
        if (peek('-')) {
            pos++;
        }
        boolean isDouble = false;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                if (c == '.' || c == 'e' || c == 'E') {
                    isDouble = true;
                }
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw new JsonException("Expected value at offset " + start);
        }
        String text = input.substring(start, pos);
        try {
            if (isDouble) {
                return Double.valueOf(text);
            }
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new JsonException("Bad number: " + text);
        }
    }

    private void expectLiteral(String literal) {
        if (!input.startsWith(literal, pos)) {
            throw new JsonException("Expected '" + literal + "' at offset " + pos);
        }
        pos += literal.length();
    }

    private void expectChar(char expected) {
        if (pos >= input.length() || input.charAt(pos) != expected) {
            throw new JsonException("Expected '" + expected + "' at offset " + pos);
        }
        pos++;
    }

    private boolean peek(char c) {
        return pos < input.length() && input.charAt(pos) == c;
    }

    private void skipWhitespace() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }
}
