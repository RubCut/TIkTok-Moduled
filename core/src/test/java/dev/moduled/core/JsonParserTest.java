package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class JsonParserTest {

    @Test
    @SuppressWarnings("unchecked")
    public void parsesManifestShape() {
        Object parsed = JsonParser.parse("{\"id\":\"region.module\",\"versionCode\":12,"
                + "\"targetPackages\":[\"com.zhiliaoapp.musically\"],\"ok\":true,\"none\":null}");
        Map<String, Object> map = (Map<String, Object>) parsed;
        assertEquals("region.module", map.get("id"));
        assertEquals(12L, map.get("versionCode"));
        assertEquals(List.of("com.zhiliaoapp.musically"), map.get("targetPackages"));
        assertEquals(Boolean.TRUE, map.get("ok"));
        assertTrue(map.containsKey("none"));
        assertNull(map.get("none"));
    }

    /** PKG-04: дублирующиеся ключи отклоняются, а не тихо перезаписываются. */
    @Test
    public void rejectsDuplicateKeys() {
        assertJsonFails("{\"id\":\"a\",\"id\":\"b\"}", "Duplicate key");
    }

    @Test
    public void rejectsTrailingGarbage() {
        assertJsonFails("{\"a\":1} extra", "Trailing");
    }

    @Test
    public void rejectsControlCharacterInString() {
        assertJsonFails("{\"a\":\"b\u0001c\"}", "Control character");
    }

    @Test
    public void rejectsBadEscape() {
        assertJsonFails("{\"a\":\"\\q\"}", "Bad escape");
    }

    @Test
    public void rejectsUnterminatedString() {
        assertJsonFails("{\"a\":\"b}", "Unterminated string");
    }

    @Test
    public void rejectsLeadingZeroFreeMalformedNumber() {
        assertJsonFails("{\"a\":1.2.3}", "Bad number");
    }

    @Test
    public void decodesEscapes() {
        assertEquals("a\"b\\c\nd\te\u0041", JsonParser.parse("\"a\\\"b\\\\c\\nd\\te\\u0041\""));
    }

    @Test
    public void distinguishesIntegerAndDouble() {
        assertTrue(JsonParser.parse("3") instanceof Long);
        assertTrue(JsonParser.parse("3.0") instanceof Double);
    }

    private static void assertJsonFails(String input, String expectedInMessage) {
        try {
            JsonParser.parse(input);
            fail("Expected rejection for: " + input);
        } catch (JsonParser.JsonException e) {
            assertTrue("Message '" + e.getMessage() + "' should contain '" + expectedInMessage + "'",
                    e.getMessage().contains(expectedInMessage));
        }
    }
}
