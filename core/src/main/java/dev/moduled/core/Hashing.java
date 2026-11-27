package dev.moduled.core;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 хеширование (ТЗ §10.2, §14.2). SHA-256 проверяет целостность,
 * но сам по себе не удостоверяет автора — см. docs/DECISIONS.md ADR-004.
 */
public final class Hashing {
    private Hashing() {
    }

    public static String sha256Hex(byte[] data) {
        MessageDigest digest = newDigest();
        digest.update(data);
        return toHex(digest.digest());
    }

    public static String sha256Hex(InputStream in, long maxBytes) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Stream exceeds limit of " + maxBytes + " bytes");
            }
            digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    public static boolean isSha256Hex(String s) {
        if (s == null || s.length() != 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 гарантированно доступен на каждой JVM/Android.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = Character.forDigit(v >>> 4, 16);
            out[i * 2 + 1] = Character.forDigit(v & 0x0F, 16);
        }
        return new String(out);
    }
}
