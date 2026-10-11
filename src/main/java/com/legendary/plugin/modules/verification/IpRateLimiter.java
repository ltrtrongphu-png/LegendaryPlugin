package com.legendary.plugin.modules.verification;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window per-IP join rate limiter, used to blunt bot-net join
 * floods before they ever reach the verification limbo. Ported from
 * SmartOptimizer/BaB's IpRateLimiter (both plugins had one; merged here).
 */
public final class IpRateLimiter {

    private static final class Window {
        int count;
        long windowStart;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final long windowMillis = 60_000L;

    public synchronized boolean isOverLimit(String ip, int maxPerMinute) {
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(ip, k -> {
            Window nw = new Window();
            nw.windowStart = now;
            return nw;
        });
        if (now - w.windowStart > windowMillis) {
            w.windowStart = now;
            w.count = 0;
        }
        w.count++;
        return w.count > maxPerMinute;
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().windowStart > windowMillis * 5);
    }
}
