package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** §13.2: запрет HTTP downgrade и локальных источников. */
public class UrlPolicyTest {

    @Test
    public void allowsPublicHttps() {
        assertTrue(UrlPolicy.isAllowedHttps("https://author.example/module.json"));
        assertTrue(UrlPolicy.isAllowedHttps("https://raw.githubusercontent.com/a/b/main/m.json"));
        assertTrue(UrlPolicy.check("https://catalog.example/index.json").isOk());
    }

    @Test
    public void rejectsHttpDowngrade() {
        assertFalse(UrlPolicy.isAllowedHttps("http://author.example/module.json"));
        Result<Void> result = UrlPolicy.check("http://author.example/module.json");
        assertEquals("URL_NOT_HTTPS", result.errorCode());
        assertTrue(result.errorMessage().contains("HTTPS"));
    }

    @Test
    public void rejectsCredentialsAndLocalAddresses() {
        assertFalse(UrlPolicy.isAllowedHttps("https://user:pass@author.example/m.json"));
        assertFalse(UrlPolicy.isAllowedHttps("https://localhost/m.json"));
        assertFalse(UrlPolicy.isAllowedHttps("https://127.0.0.1/m.json"));
        assertFalse(UrlPolicy.isAllowedHttps("https://192.168.1.10/m.json"));
        assertFalse(UrlPolicy.isAllowedHttps("https://10.0.0.5/m.json"));
        assertFalse(UrlPolicy.isAllowedHttps("https://[::1]/m.json"));
    }

    @Test
    public void rejectsOtherSchemesAndMalformedUrls() {
        assertFalse(UrlPolicy.isAllowedHttps("file:///sdcard/module.zip"));
        assertFalse(UrlPolicy.isAllowedHttps("content://dev.moduled.manager.bridge/dex/a.b"));
        assertFalse(UrlPolicy.isAllowedHttps("ftp://author.example/m.json"));
        assertFalse(UrlPolicy.isAllowedHttps("https://"));
        assertFalse(UrlPolicy.isAllowedHttps("not a url"));
        assertFalse(UrlPolicy.isAllowedHttps(null));
        assertFalse(UrlPolicy.isAllowedHttps(""));
        assertEquals("URL_EMPTY", UrlPolicy.check("").errorCode());
    }

    @Test
    public void rejectsOverlongUrls() {
        StringBuilder sb = new StringBuilder("https://author.example/");
        for (int i = 0; i < UrlPolicy.MAX_URL_LENGTH; i++) {
            sb.append('a');
        }
        assertFalse(UrlPolicy.isAllowedHttps(sb.toString()));
        assertEquals("URL_TOO_LONG", UrlPolicy.check(sb.toString()).errorCode());
    }

    @Test
    public void fragmentsAreRejected() {
        assertFalse(UrlPolicy.isAllowedHttps("https://author.example/m.json#part"));
    }
}
