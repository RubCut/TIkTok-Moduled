package dev.moduled.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ТЗ §16.2: разбор конфига региона, терпимость к пустому и частичному вводу. */
public class RegionConfigTest {

    @Test
    public void parsesFullConfig() {
        RegionConfig config = RegionConfig.parse(
                "# комментарий\nregion=us\nlanguage=EN\nsimCountry=gb\nverifyServerResponse=true\n");
        assertEquals("US", config.region);
        assertEquals("en", config.language);
        assertEquals("GB", config.simCountry);
        assertTrue(config.verifyServerResponse);
        assertTrue(config.unknownKeys.isEmpty());
        assertFalse(config.isEmpty());
        assertEquals("en", config.locale().getLanguage());
        assertEquals("US", config.locale().getCountry());
    }

    @Test
    public void emptyConfigIsAllowed() {
        RegionConfig config = RegionConfig.parse("");
        assertTrue(config.isEmpty());
        RegionConfig nullConfig = RegionConfig.parse(null);
        assertTrue(nullConfig.isEmpty());
    }

    @Test
    public void unknownKeysAreReportedNotDropped() {
        RegionConfig config = RegionConfig.parse("region=US\nipAddress=1.2.3.4\ngps=59.33,18.07\n");
        assertEquals("US", config.region);
        assertTrue(config.unknownKeys.containsKey("ipAddress"));
        assertTrue(config.unknownKeys.containsKey("gps"));
    }

    @Test
    public void malformedLinesDoNotBreakParsing() {
        RegionConfig config = RegionConfig.parse("=novalue\nregion=SE\nbroken-line\nlanguage=sv");
        assertEquals("SE", config.region);
        assertEquals("sv", config.language);
        assertTrue(config.unknownKeys.containsKey("broken-line"));
    }
}
