package com.legendary.plugin.modules.optimization;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple fixed-window rate limiter keyed by an arbitrary string
 * (e.g. a chunk key), used by RedstoneLimiterListener to throttle
 * redstone-clock lag machines. Ported from SmartOptimizer.RateLimiter.
 */
public final class RateLimiter {

    private static final class Counter {
        final AtomicInteger count = new AtomicInteger();
        volatile long windowStart;
        Counter(long now) { this.windowStart = now; }
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final long windowMillis;

    public RateLimiter(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public boolean isOverLimit(String key, int maxEventsPerWindow, long nowMillis) {
        Counter counter = counters.computeIfAbsent(key, k -> new Counter(nowMillis));
        if (nowMillis - counter.windowStart > windowMillis) {
            synchronized (counter) {
                if (nowMillis - counter.windowStart > windowMillis) {
                    counter.windowStart = nowMillis;
                    counter.count.set(0);
                }
            }
        }
        return counter.count.incrementAndGet() > maxEventsPerWindow;
    }

    public void cleanupStale(long nowMillis, long staleAfterMillis) {
        counters.entrySet().removeIf(e -> nowMillis - e.getValue().windowStart > staleAfterMillis);
    }

    public int size() {
        return counters.size();
    }
}
