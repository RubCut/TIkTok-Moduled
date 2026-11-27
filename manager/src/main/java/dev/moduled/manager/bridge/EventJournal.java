package dev.moduled.manager.bridge;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Журнал событий, полученных от runtime (ТЗ §5.2 п.8, §22).
 *
 * <p>Ограничен по объёму: последние 200 событий. Это копия для экрана
 * диагностики; авторитетное состояние защиты хранится в хосте (CR-07).</p>
 */
final class EventJournal {

    private static final int MAX_EVENTS = 200;

    private EventJournal() {
    }

    static synchronized void append(Context context, String eventType, String eventKey) {
        File file = journalFile(context);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            String line = System.currentTimeMillis() + '|' + eventType + '|' + eventKey + '\n';
            Files.write(file.toPath(), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            List<String> lines = read(context);
            if (lines.size() > MAX_EVENTS) {
                List<String> trimmed = new ArrayList<>(lines.subList(lines.size() - MAX_EVENTS, lines.size()));
                Files.write(file.toPath(), String.join("",
                        trimmed.toArray(new String[0])).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
            // Журнал событий не должен ломать мост.
        }
    }

    static synchronized List<String> read(Context context) {
        List<String> lines = new ArrayList<>();
        File file = journalFile(context);
        if (!file.isFile()) {
            return lines;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (!line.isEmpty()) {
                    lines.add(line + "\n");
                }
            }
        } catch (IOException ignored) {
            // Пустой журнал при ошибке чтения.
        }
        return lines;
    }

    private static File journalFile(Context context) {
        return new File(new File(context.getFilesDir(), "tmoduled/logs"), "bridge-events.log");
    }
}
