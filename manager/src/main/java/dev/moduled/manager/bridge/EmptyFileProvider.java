package dev.moduled.manager.bridge;

import java.io.File;
import java.io.IOException;

/**
 * Пустой конфиг для модуля, который его ещё не создал (ТЗ §12 CFG-02: пустой
 * файл допустим). Мост отдаёт реальный файл, а не фиктивный поток.
 */
final class EmptyFileProvider {

    private EmptyFileProvider() {
    }

    static File materialize(File storageRoot, String moduleId) {
        File dir = new File(storageRoot, moduleId);
        File config = new File(dir, "config.txt");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        try {
            if (!config.isFile() && !config.createNewFile()) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return config;
    }
}
