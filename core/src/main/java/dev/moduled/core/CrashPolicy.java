package dev.moduled.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Политика аварийной защиты (ТЗ §18.1 CR-01..CR-08).
 *
 * <ul>
 *   <li>CR-01: «более двух крашей» = три подтверждённых сбоя за скользящие 10 минут.</li>
 *   <li>CR-02: после третьего сбоя следующий запуск — safe mode, защёлка не снимается
 *       обычным рестартом.</li>
 *   <li>CR-03: safe mode блокирует все пользовательские и комплектные модули.</li>
 *   <li>CR-04: авария хоста не доказывает виновность конкретного модуля.</li>
 *   <li>CR-05: force stop / штатный выход / low memory не считаются крашами;
 *       ANR — отдельный тип и отдельный счётчик.</li>
 *   <li>CR-06: повторный учёт одного exit event исключён (дедупликация по eventId).</li>
 *   <li>CR-07: сброс — явное действие с указанием последствий.</li>
 *   <li>CR-08: повреждённое состояние → консервативный safe mode + диагностика.</li>
 * </ul>
 *
 * <p>Класс чистый (без Android): время подаётся вызывающим кодом через {@link Clock},
 * персистентность — через {@link StateStore}. Окно считается по монотонным часам,
 * чтобы перевод системных часов не ломал политику.</p>
 */
public final class CrashPolicy {
    /** Скользящее окно: 10 минут (CR-01). */
    public static final long WINDOW_MS = 10L * 60 * 1000;

    /** Порог: третий подтверждённый сбой (CR-01). */
    public static final int CRASH_THRESHOLD = 3;

    public enum EventType {
        CRASH,
        ANR,
        FORCE_STOP,
        NORMAL_EXIT,
        LOW_MEMORY,
        UNKNOWN
    }

    public interface Clock {
        /** Монотонные миллисекунды (SystemClock.elapsedRealtime на Android). */
        long monotonicMs();

        /** Стена (System.currentTimeMillis) — только для отображения. */
        long wallMs();
    }

    public interface StateStore {
        /** @return сохранённое состояние или null, если его нет. */
        String load();

        void save(String state);

        void clear();
    }

    public static final class Event {
        public final String eventId;
        public final EventType type;
        public final long monotonicMs;
        public final long wallMs;

        public Event(String eventId, EventType type, long monotonicMs, long wallMs) {
            this.eventId = eventId;
            this.type = type;
            this.monotonicMs = monotonicMs;
            this.wallMs = wallMs;
        }
    }

    public static final class State {
        public boolean safeModeLatched;
        public boolean stateCorrupted;
        public final List<Event> crashEvents = new ArrayList<>();
        public final List<Event> anrEvents = new ArrayList<>();
        public final Set<String> seenEventIds = new HashSet<>();
        public long lastResetWallMs;
    }

    private final Clock clock;
    private final StateStore store;
    private State state;

    public CrashPolicy(Clock clock, StateStore store) {
        this.clock = clock;
        this.store = store;
        this.state = loadOrFresh();
    }

    /** Записать событие. Возвращает true, если событие учтено как подтверждённый краш. */
    public synchronized boolean recordEvent(Event event) {
        if (event == null || event.eventId == null) {
            return false;
        }
        // CR-06: повторный учёт одного exit event запрещён.
        if (state.seenEventIds.contains(event.eventId)) {
            return false;
        }
        state.seenEventIds.add(event.eventId);

        boolean counted = false;
        if (event.type == EventType.CRASH) {
            state.crashEvents.add(event);
            counted = true;
        } else if (event.type == EventType.ANR) {
            // CR-05: ANR — отдельный счётчик, не добавляется в счётчик крашей.
            state.anrEvents.add(event);
        }
        // FORCE_STOP / NORMAL_EXIT / LOW_MEMORY / UNKNOWN: не считаются (CR-05).

        pruneWindow();
        if (state.crashEvents.size() >= CRASH_THRESHOLD) {
            state.safeModeLatched = true;
        }
        persist();
        return counted;
    }

    /** Должен ли текущий запуск идти в safe mode (CR-02, CR-03, CR-08). */
    public synchronized boolean shouldRunSafeMode() {
        if (state.stateCorrupted) {
            return true;
        }
        pruneWindow();
        return state.safeModeLatched;
    }

    /** Явный сброс защёлки (CR-07). Вызывающий код обязан показать последствия. */
    public synchronized void reset() {
        state.safeModeLatched = false;
        state.stateCorrupted = false;
        state.crashEvents.clear();
        state.anrEvents.clear();
        state.seenEventIds.clear();
        state.lastResetWallMs = clock.wallMs();
        persist();
    }

    public synchronized int recentCrashCount() {
        pruneWindow();
        return state.crashEvents.size();
    }

    public synchronized int recentAnrCount() {
        pruneWindow();
        return state.anrEvents.size();
    }

    public synchronized boolean isCorrupted() {
        return state.stateCorrupted;
    }

    private void pruneWindow() {
        long now = clock.monotonicMs();
        long cutoff = now - WINDOW_MS;
        state.crashEvents.removeIf(e -> e.monotonicMs < cutoff);
        state.anrEvents.removeIf(e -> e.monotonicMs < cutoff);
    }

    private State loadOrFresh() {
        String raw = null;
        try {
            raw = store.load();
        } catch (RuntimeException ignored) {
            // Недоступность хранилища не должна ронять запуск.
        }
        if (raw == null || raw.isEmpty()) {
            return new State();
        }
        try {
            return decode(raw);
        } catch (RuntimeException e) {
            // CR-08: повреждённое состояние → консервативный safe mode.
            State corrupted = new State();
            corrupted.stateCorrupted = true;
            corrupted.safeModeLatched = true;
            return corrupted;
        }
    }

    private void persist() {
        try {
            store.save(encode(state));
        } catch (RuntimeException ignored) {
            // Не удалось сохранить — следующий запуск увидит старое состояние;
            // это безопаснее, чем падать в критическом пути.
        }
    }

    // --- Сериализация: простой построчный формат, без внешних зависимостей. ---

    private static String encode(State s) {
        StringBuilder sb = new StringBuilder();
        sb.append("v1\n");
        sb.append("safeMode ").append(s.safeModeLatched ? '1' : '0').append('\n');
        sb.append("corrupted ").append(s.stateCorrupted ? '1' : '0').append('\n');
        sb.append("lastReset ").append(s.lastResetWallMs).append('\n');
        for (Event e : s.crashEvents) {
            sb.append("crash ").append(e.eventId).append(' ').append(e.monotonicMs).append(' ')
                    .append(e.wallMs).append('\n');
        }
        for (Event e : s.anrEvents) {
            sb.append("anr ").append(e.eventId).append(' ').append(e.monotonicMs).append(' ')
                    .append(e.wallMs).append('\n');
        }
        for (String id : s.seenEventIds) {
            sb.append("seen ").append(id).append('\n');
        }
        return sb.toString();
    }

    private static State decode(String raw) {
        String[] lines = raw.split("\n");
        if (lines.length == 0 || !"v1".equals(lines[0])) {
            throw new IllegalArgumentException("Unknown state version");
        }
        State s = new State();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(" ");
            switch (parts[0]) {
                case "safeMode":
                    s.safeModeLatched = "1".equals(parts[1]);
                    break;
                case "corrupted":
                    s.stateCorrupted = "1".equals(parts[1]);
                    break;
                case "lastReset":
                    s.lastResetWallMs = Long.parseLong(parts[1]);
                    break;
                case "crash":
                    s.crashEvents.add(new Event(parts[1], EventType.CRASH,
                            Long.parseLong(parts[2]), Long.parseLong(parts[3])));
                    s.seenEventIds.add(parts[1]);
                    break;
                case "anr":
                    s.anrEvents.add(new Event(parts[1], EventType.ANR,
                            Long.parseLong(parts[2]), Long.parseLong(parts[3])));
                    s.seenEventIds.add(parts[1]);
                    break;
                case "seen":
                    s.seenEventIds.add(parts[1]);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown line: " + line);
            }
        }
        return s;
    }
}
