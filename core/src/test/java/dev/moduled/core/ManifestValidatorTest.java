package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ManifestValidatorTest {

    private static final String VALID = "{"
            + "\"id\":\"region.module\","
            + "\"name\":\"Регион\","
            + "\"description\":\"Комплектный модуль региона\","
            + "\"version\":\"1.0.0\","
            + "\"versionCode\":10,"
            + "\"apiVersion\":1,"
            + "\"entrypoint\":\"dev.moduled.region.RegionModule\","
            + "\"targetPackages\":[\"com.zhiliaoapp.musically\"]"
            + "}";

    @Test
    public void acceptsCompleteManifest() {
        Result<ModuleManifest> result = ManifestValidator.validate(bytes(VALID));
        assertTrue(result.errorMessage(), result.isOk());
        ModuleManifest manifest = result.value();
        assertEquals("region.module", manifest.id);
        assertEquals(10L, manifest.versionCode);
        assertEquals(1, manifest.apiVersion);
        assertEquals("dev.moduled.region.RegionModule", manifest.entrypoint);
        assertTrue(manifest.targetsPackage("com.zhiliaoapp.musically"));
        assertTrue(!manifest.targetsPackage("com.other.app"));
    }

    @Test
    public void rejectsEachMissingMandatoryField() {
        String[] fields = {"id", "name", "description", "version", "versionCode",
                "apiVersion", "entrypoint", "targetPackages"};
        for (String field : fields) {
            String stripped = removeField(VALID, field);
            Result<ModuleManifest> result = ManifestValidator.validate(bytes(stripped));
            assertTrue("Missing field '" + field + "' must be rejected", !result.isOk());
            assertTrue("Rejection must carry an error code", result.errorCode().startsWith("MANIFEST_"));
        }
    }

    /** PKG-04: дубли ключа в manifest — отказ. */
    @Test
    public void rejectsDuplicateManifestKeys() {
        String duplicated = "{\"id\":\"a.b\",\"id\":\"c.d\"}";
        Result<ModuleManifest> result = ManifestValidator.validate(bytes(duplicated));
        assertTrue(!result.isOk());
        assertEquals("MANIFEST_BAD_JSON", result.errorCode());
    }

    /** §9.2: неизвестные поля отклоняются, а не молча игнорируются. */
    @Test
    public void rejectsUnknownFields() {
        String withExtra = VALID.substring(0, VALID.length() - 1) + ",\"futureFlag\":true}";
        Result<ModuleManifest> result = ManifestValidator.validate(bytes(withExtra));
        assertTrue(!result.isOk());
        assertEquals("MANIFEST_UNKNOWN_FIELD", result.errorCode());
    }

    /** §9.2: модуль с более новой apiVersion отклоняется понятной причиной. */
    @Test
    public void rejectsUnsupportedApiVersion() {
        Result<ModuleManifest> result = ManifestValidator.validate(
                bytes(VALID.replace("\"apiVersion\":1", "\"apiVersion\":2")));
        assertTrue(!result.isOk());
        assertEquals("MANIFEST_UNSUPPORTED_API", result.errorCode());
        assertTrue(result.errorMessage().contains("apiVersion 2"));
    }

    @Test
    public void rejectsWrongTypes() {
        assertTrue(!ManifestValidator.validate(
                bytes(VALID.replace("\"versionCode\":10", "\"versionCode\":\"10\""))).isOk());
        assertTrue(!ManifestValidator.validate(
                bytes(VALID.replace("\"versionCode\":10", "\"versionCode\":-1"))).isOk());
        assertTrue(!ManifestValidator.validate(
                bytes(VALID.replace("\"targetPackages\":[\"com.zhiliaoapp.musically\"]",
                        "\"targetPackages\":\"com.zhiliaoapp.musically\""))).isOk());
        assertTrue(!ManifestValidator.validate(
                bytes(VALID.replace("\"targetPackages\":[\"com.zhiliaoapp.musically\"]",
                        "\"targetPackages\":[]"))).isOk());
        assertEquals("MANIFEST_BAD_ENTRYPOINT", ManifestValidator.validate(
                bytes(VALID.replace("dev.moduled.region.RegionModule", "not a class"))).errorCode());
        assertEquals("MANIFEST_BAD_ID", ManifestValidator.validate(
                bytes(VALID.replace("\"id\":\"region.module\"", "\"id\":\"../evil\""))).errorCode());
    }

    @Test
    public void rejectsNonUtf8Manifest() {
        byte[] bad = VALID.getBytes(StandardCharsets.UTF_8);
        // Портим многобайтовую последовательность внутри кириллического имени.
        for (int i = 0; i < bad.length; i++) {
            if ((bad[i] & 0xFF) == 0xD0) {
                bad[i] = (byte) 0xFF;
                break;
            }
        }
        Result<ModuleManifest> result = ManifestValidator.validate(bad);
        assertTrue(!result.isOk());
        assertEquals("MANIFEST_NOT_UTF8", result.errorCode());
    }

    @Test
    public void rejectsEmptyAndOversizedManifest() {
        assertEquals("MANIFEST_EMPTY", ManifestValidator.validate(new byte[0]).errorCode());
        assertEquals("MANIFEST_EMPTY", ManifestValidator.validate(null).errorCode());
        byte[] big = new byte[Limits.MAX_DESCRIPTOR_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        assertEquals("MANIFEST_TOO_LARGE", ManifestValidator.validate(big).errorCode());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String removeField(String json, String field) {
        String withValue = "\"" + field + "\":";
        int start = json.indexOf(withValue);
        if (start < 0) {
            throw new IllegalArgumentException("field not found: " + field);
        }
        int valueStart = start + withValue.length();
        int end = valueStart;
        if (json.charAt(valueStart) == '[') {
            end = json.indexOf(']', valueStart) + 1;
        } else if (json.charAt(valueStart) == '"') {
            end = json.indexOf('"', valueStart + 1) + 1;
        } else {
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                end++;
            }
        }
        String removed = json.substring(0, start) + json.substring(end);
        return removed.replace("{,", "{").replace(",}", "}").replace(",,", ",");
    }
}
