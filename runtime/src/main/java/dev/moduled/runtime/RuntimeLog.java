package dev.moduled.runtime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Журнал runtime (ТЗ §22): время, уровень, процесс, session ID, компонент/ID
 * модуля и сообщение. Бюджет — до 5 MiB на процесс с ротацией: при превышении
 * старая половина файла отбрасывается, а событие усечения фиксируется явно.
 *
 * <p>Класс работает только с java.io/java.nio, поэтому тестируется на JVM без
 * Android-эмулятора. Запись не выполняется в критическом IPC-пути старта: вызовы
 * из bootstrap идут через буферизованный append и fsync только при ошибках.</p>
 */
public final class RuntimeLog {

    private final File file;
    private final long budgetBytes;
    private final String sessionId;
    private final String processName;
    private final Object lock = new Object();

    public RuntimeLog(File file, String processName) {
        this(file, processName, dev.moduled.core.Limits.MAX_RUNTIME_LOG_BYTES);
    }

    public RuntimeLog(File file, String processName, long budgetBytes) {
        this.file = file;
        this.processName = processName;
        this.budgetBytes = budgetBytes;
        this.sessionId = UUID.randomUUID().toString();
    }

    public String sessionId() {
        return sessionId;
    }

    public File file() {
        return file;
    }

    public void log(RuntimeEvent event, String moduleId, String message) {
        write(System.currentTimeMillis(), event, moduleId, message, null);
    }

    public void log(RuntimeEvent event, String moduleId, String message, Throwable throwable) {
        write(System.currentTimeMillis(), event, moduleId, message, throwable);
    }

    /** Формат строки: {@code wall|level|process|session|event|moduleId|message}. */
    private void write(long wallMs, RuntimeEvent event, String moduleId, String message,
                       Throwable throwable) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(wallMs).append('|')
                .append(levelOf(event)).append('|')
                .append(escape(processName)).append('|')
                .append(sessionId).append('|')
                .append(event.name()).append('|')
                .append(escape(moduleId == null ? "-" : moduleId)).append('|')
                .append(escape(message == null ? "" : message));
        if (throwable != null) {
            sb.append('|').append(escape(throwable.toString()));
        }
        sb.append('\n');
        byte[] line = sb.toString().getBytes(StandardCharsets.UTF_8);

        synchronized (lock) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    return; // Журнал не должен ронять процесс хоста.
                }
                if (file.length() + line.length > budgetBytes) {
                    rotate(file.length() + line.length - budgetBytes);
                }
                Files.write(file.toPath(), line,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException ignored) {
                // Отказ журнала не является причиной падения модуля или хоста.
            }
        }
    }

    /** Отбрасывает oldest байты, оставляя целые строки. */
    private void rotate(long overflowBytes) throws IOException {
        byte[] all = Files.readAllBytes(file.toPath());
        int cut = (int) Math.min(all.length, Math.max(0, overflowBytes + all.length / 2));
        // Двигаем cut к началу следующей строки, чтобы не оставлять обрывок.
        while (cut < all.length && all[cut] != '\n') {
            cut++;
        }
        if (cut < all.length) {
            cut++;
        }
        byte[] kept = new byte[all.length - cut];
        System.arraycopy(all, cut, kept, 0, kept.length);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(0);
            raf.write(kept);
        }
        String marker = (System.currentTimeMillis() + "|" + levelOf(RuntimeEvent.LOG_TRUNCATED)
                + '|' + processName + '|' + sessionId + '|' + RuntimeEvent.LOG_TRUNCATED.name()
                + "|-|Log rotated, older entries dropped\n");
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(raf.length());
            raf.write(marker.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Читает журнал для экрана диагностики; повреждённые строки пропускаются. */
    public List<String> readEntries() {
        synchronized (lock) {
            List<String> entries = new ArrayList<>();
            if (!file.isFile()) {
                return entries;
            }
            try {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    if (!line.isEmpty()) {
                        entries.add(line);
                    }
                }
            } catch (IOException e) {
                entries.add("log-read-error|" + e.getMessage());
            }
            return entries;
        }
    }

    public long sizeBytes() {
        return file.isFile() ? file.length() : 0L;
    }

    private static char levelOf(RuntimeEvent event) {
        switch (event) {
            case MODULE_ERROR:
            case HOOK_FAILED:
            case CRASH_REPORTED:
            case SNAPSHOT_REJECTED:
                return 'E';
            case SAFE_MODE_ACTIVE:
            case MODULE_BLOCKED_SAFE_MODE:
            case MODULE_INCOMPATIBLE:
            case SNAPSHOT_STALE:
            case SNAPSHOT_UNAVAILABLE:
            case LOG_TRUNCATED:
            case RESTART_REJECTED:
                return 'W';
            case BOOTSTRAP_DONE:
            case MODULE_STARTED:
            case SNAPSHOT_IMPORTED:
            case HOOK_INSTALLED:
                return 'I';
            default:
                return 'D';
        }
    }

    private static String escape(String value) {
        return value.replace("|", "/").replace("\n", " ").replace("\r", " ");
    }
}
