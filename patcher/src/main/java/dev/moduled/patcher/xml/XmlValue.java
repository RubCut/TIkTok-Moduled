package dev.moduled.patcher.xml;

/**
 * Значение атрибута бинарного XML Android (AXML).
 * Типы соответствуют {@code TypedValue}: строка, целое, boolean, ссылка на ресурс.
 */
public final class XmlValue {

    public static final int TYPE_NULL = 0x00;
    public static final int TYPE_REFERENCE = 0x01;
    public static final int TYPE_STRING = 0x03;
    public static final int TYPE_INT_DEC = 0x10;
    public static final int TYPE_INT_HEX = 0x11;
    public static final int TYPE_INT_BOOLEAN = 0x12;

    public final int type;
    public final String stringValue;
    public final long intValue;

    public XmlValue(int type, String stringValue, long intValue) {
        this.type = type;
        this.stringValue = stringValue;
        this.intValue = intValue;
    }

    public static XmlValue string(String value) {
        return new XmlValue(TYPE_STRING, value, 0);
    }

    public static XmlValue integer(long value) {
        return new XmlValue(TYPE_INT_DEC, null, value);
    }

    public static XmlValue bool(boolean value) {
        return new XmlValue(TYPE_INT_BOOLEAN, null, value ? -1 : 0);
    }

    public String asString() {
        if (type == TYPE_STRING) {
            return stringValue;
        }
        if (type == TYPE_INT_BOOLEAN) {
            return intValue != 0 ? "true" : "false";
        }
        return stringValue != null ? stringValue : Long.toString(intValue);
    }

    public Integer asInteger() {
        if (type == TYPE_INT_DEC || type == TYPE_INT_HEX || type == TYPE_INT_BOOLEAN) {
            return (int) intValue;
        }
        if (type == TYPE_STRING && stringValue != null) {
            try {
                return Integer.decode(stringValue);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "XmlValue{type=0x" + Integer.toHexString(type) + ", value=" + asString() + '}';
    }
}
