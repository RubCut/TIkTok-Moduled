package dev.moduled.runtime;

import dev.moduled.api.ConfigSnapshot;
import java.nio.charset.StandardCharsets;

/**
 * Снимок конфига, выдаваемый модулю (ТЗ §12 CFG-09, CFG-10).
 * Байты не преобразуются: модуль получает ровно то, что сохранил пользователь.
 */
public final class RuntimeConfigSnapshot implements ConfigSnapshot {

    private final byte[] bytes;
    private final String sha256;
    private final long generation;

    public RuntimeConfigSnapshot(byte[] bytes, String sha256, long generation) {
        this.bytes = bytes != null ? bytes.clone() : new byte[0];
        this.sha256 = sha256;
        this.generation = generation;
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public String sha256() {
        return sha256;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }
}
