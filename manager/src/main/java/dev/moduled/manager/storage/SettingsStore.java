package dev.moduled.manager.storage;

import dev.moduled.core.AtomicFile;
import dev.moduled.core.Result;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Настройки TModules (ТЗ §21.2 экран 9): тема, приватность журналов,
 * автокопирование crash-отчёта. Значения хранятся простым текстом и пишутся
 * атомарно; повреждённый файл не роняет менеджер, а даёт значения по умолчанию.
 */
public final class SettingsStore {

    public static final String KEY_THEME = "theme";
    public static final String KEY_AUTO_COPY_CRASH = "autoCopyCrashReport";
    public static final String KEY_LOG_PRIVACY = "logPrivacy";
    public static final String KEY_FIRST_RUN_SHOWN = "firstRunShown";
    public static final String KEY_LAST_CATALOG_UPDATE = "lastCatalogUpdateMs";

    /** Уровень приватности журнала (§22). */
    public enum LogPrivacy {
        /** Только события без содержимого конфигов. */
        MINIMAL,
        /** События и технические подробности. */
        NORMAL,
        /** Подробно, включая сообщения модулей. */
        VERBOSE
    }

    private final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();

    public SettingsStore(File file) {
        this.file = file.toPath();
        load();
    }

    private void load() {
        values.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    values.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (IOException e) {
            // Повреждённые настройки читаются как пустые: значения по умолчанию.
        }
    }

    public String getString(String key, String fallback) {
        String value = values.get(key);
        return value != null ? value : fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        return "true".equals(value);
    }

    public long getLong(String key, long fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Result<Void> setString(String key, String value) {
        values.put(key, value != null ? value : "");
        return flush();
    }

    public Result<Void> setBoolean(String key, boolean value) {
        return setString(key, value ? "true" : "false");
    }

    public Result<Void> setLong(String key, long value) {
        return setString(key, Long.toString(value));
    }

    private Result<Void> flush() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append(entry.getKey()).append('=')
                    .append(entry.getValue().replace("\n", " ")).append('\n');
        }
        return AtomicFile.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public LogPrivacy logPrivacy() {
        try {
            return LogPrivacy.valueOf(getString(KEY_LOG_PRIVACY, LogPrivacy.MINIMAL.name()));
        } catch (IllegalArgumentException e) {
            return LogPrivacy.MINIMAL;
        }
    }

    public Result<Void> setLogPrivacy(LogPrivacy privacy) {
        return setString(KEY_LOG_PRIVACY, privacy.name());
    }

    /**
     * Автокопирование crash-отчёта включается только явным согласием: логи могут
     * содержать личные данные (ТЗ §18.3, §22).
     */
    public boolean autoCopyCrashReport() {
        return getBoolean(KEY_AUTO_COPY_CRASH, false);
    }
}
