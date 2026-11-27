package dev.moduled.runtime;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Ограничение частоты запросов перезапуска (ТЗ §19): перезапуск не должен
 * создавать бесконечный цикл и не должен сбрасывать счётчик сбоев.
 */
public final class RestartRateLimiter {

    private final int maxRequests;
    private final long windowMs;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    /** По умолчанию: не более 2 запросов за 5 минут. */
    public RestartRateLimiter() {
        this(2, 5L * 60 * 1000);
    }

    public RestartRateLimiter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    /**
     * Регистрирует запрос, если он проходит по частоте.
     *
     * @param nowMs монотонное время в миллисекундах
     * @return true, если запрос разрешён
     */
    public synchronized boolean allow(long nowMs) {
        long cutoff = nowMs - windowMs;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }
        if (timestamps.size() >= maxRequests) {
            return false;
        }
        timestamps.addLast(nowMs);
        return true;
    }

    public synchronized int recentRequests(long nowMs) {
        long cutoff = nowMs - windowMs;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }
        return timestamps.size();
    }

    public synchronized void reset() {
        timestamps.clear();
    }
}
