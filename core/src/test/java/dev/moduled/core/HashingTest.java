package dev.moduled.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HashingTest {

    /** Известный вектор SHA-256 (ТЗ §10.2: SHA-256 для целостности). */
    @Test
    public void sha256_knownVector_abc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Hashing.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void sha256_knownVector_empty() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Hashing.sha256Hex(new byte[0]));
    }

    @Test
    public void sha256Stream_matchesArrayHash() throws Exception {
        byte[] data = "tiktok moduled".getBytes(StandardCharsets.UTF_8);
        assertEquals(Hashing.sha256Hex(data),
                Hashing.sha256Hex(new ByteArrayInputStream(data), 1024));
    }

    @Test(expected = java.io.IOException.class)
    public void sha256Stream_rejectsOversizedStream() throws Exception {
        byte[] data = new byte[10];
        Hashing.sha256Hex(new ByteArrayInputStream(data), 5);
    }

    @Test
    public void isSha256Hex_acceptsOnly64HexChars() {
        assertTrue(Hashing.isSha256Hex(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        assertTrue(Hashing.isSha256Hex(
                "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"));
        assertFalse(Hashing.isSha256Hex(null));
        assertFalse(Hashing.isSha256Hex(""));
        assertFalse(Hashing.isSha256Hex("ba7816bf"));
        assertFalse(Hashing.isSha256Hex(
                "za7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        assertFalse(Hashing.isSha256Hex(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad "));
    }
}
