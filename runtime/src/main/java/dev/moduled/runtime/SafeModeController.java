package dev.moduled.runtime;

import android.os.SystemClock;
import dev.moduled.core.CrashPolicy;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Привязка политики сбоев (ТЗ §18.1) к Android: монотонные часы, файловое
 * хранилище состояния в private storage хоста и безопасное поведение при
 * повреждении состояния (CR-08).
 *
 * <p>Защёлка хранится в файле процесса-хоста, поэтому она работает даже когда
 * менеджер (TModules) недоступен: локальная защёлка хоста предотвращает
 * повторную загрузку модулей (CR-07).</p>
 */
public final class SafeModeController {

    /** Монотонные часы Android: перевод системных часов не влияет на окно. */
    public static final class AndroidClock implements CrashPolicy.Clock {
        @Override
        public long monotonicMs() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public long wallMs() {
            return System.currentTimeMillis();
        }
    }

    /** Файловое хранилище состояния с атомарной заменой. */
    public static final class FileStateStore implements CrashPolicy.StateStore {
        private final File file;

        public FileStateStore(File file) {
            this.file = file;
        }

        @Override
        public String load() {
            if (!file.isFile()) {
                return null;
            }
            try {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read safe mode state: " + e.getMessage(), e);
            }
        }

        @Override
        public void save(String state) {
            dev.moduled.core.Result<Void> result = dev.moduled.core.AtomicFile.write(
                    file.toPath(), state.getBytes(StandardCharsets.UTF_8));
            if (!result.isOk()) {
                throw new IllegalStateException(result.errorCode() + ": " + result.errorMessage());
            }
        }

        @Override
        public void clear() {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private final CrashPolicy policy;
    private final RuntimeLog log;

    public SafeModeController(CrashPolicy policy, RuntimeLog log) {
        this.policy = policy;
        this.log = log;
    }

    public static SafeModeController create(File stateFile, RuntimeLog log) {
        return new SafeModeController(
                new CrashPolicy(new AndroidClock(), new FileStateStore(stateFile)), log);
    }

    /** True, если текущий запуск обязан идти без пользовательских и комплектных модулей. */
    public boolean isSafeModeActive() {
        boolean active = policy.shouldRunSafeMode();
        if (active) {
            log.log(RuntimeEvent.SAFE_MODE_ACTIVE, null,
                    policy.isCorrupted()
                            ? "Safe mode: состояние защиты повреждено, применён консервативный режим"
                            : "Safe mode: " + policy.recentCrashCount()
                                    + " подтверждённых сбоя за последние 10 минут");
        }
        return active;
    }

    public void recordCrashEvent(String eventKey) {
        boolean counted = policy.recordEvent(new CrashPolicy.Event(
                eventKey, CrashPolicy.EventType.CRASH,
                new AndroidClock().monotonicMs(), new AndroidClock().wallMs()));
        log.log(RuntimeEvent.CRASH_REPORTED, null,
                "Crash event " + eventKey + (counted ? " учтён" : " не учтён (дубликат или не краш)"));
    }

    public void recordEvent(CrashPolicy.Event event) {
        policy.recordEvent(event);
    }

    /** Явный сброс защёлки (CR-07): вызывается менеджером после подтверждения пользователя. */
    public void reset(String requestedBy) {
        policy.reset();
        log.log(RuntimeEvent.SAFE_MODE_RESET, null, "Safe mode сброшен: " + requestedBy);
    }

    public int recentCrashCount() {
        return policy.recentCrashCount();
    }

    public int recentAnrCount() {
        return policy.recentAnrCount();
    }

    public boolean isStateCorrupted() {
        return policy.isCorrupted();
    }

    public CrashPolicy policy() {
        return policy;
    }
}
