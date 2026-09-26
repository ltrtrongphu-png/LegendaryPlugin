package com.legendary.plugin.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Central metrics collector for the entire plugin. Every module and
 * check can record events here; the data powers the /legendary report
 * dashboard and provides a single place to inspect plugin health.
 *
 * Thread-safe; designed to be called from both main and async threads
 * without locking contention (uses lock-free atomics).
 */
public final class PluginMetrics {

    public static final class Counter {
        private final AtomicLong value = new AtomicLong();
        public void increment() { value.incrementAndGet(); }
        public void add(long n) { value.addAndGet(n); }
        public long get() { return value.get(); }
        public void reset() { value.set(0); }
    }

    public static final class Gauge {
        private volatile double value;
        public void set(double v) { this.value = v; }
        public double get() { return value; }
    }

    public static final class Timer {
        private final DoubleAdder totalMs = new DoubleAdder();
        private final AtomicLong count = new AtomicLong();
        public void record(long durationMs) { totalMs.add(durationMs); count.incrementAndGet(); }
        public double getAverageMs() {
            long c = count.get();
            return c == 0 ? 0 : totalMs.doubleValue() / c;
        }
        public long getCount() { return count.get(); }
        public void reset() { totalMs.reset(); count.set(0); }
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public Counter counter(String name) {
        return counters.computeIfAbsent(name, k -> new Counter());
    }

    public Gauge gauge(String name) {
        return gauges.computeIfAbsent(name, k -> new Gauge());
    }

    public Timer timer(String name) {
        return timers.computeIfAbsent(name, k -> new Timer());
    }

    public Map<String, Counter> getCounters() { return Map.copyOf(counters); }
    public Map<String, Gauge> getGauges() { return Map.copyOf(gauges); }
    public Map<String, Timer> getTimers() { return Map.copyOf(timers); }

    /** Resets all metrics (called on module reload). */
    public void resetAll() {
        counters.values().forEach(Counter::reset);
        gauges.values().forEach(g -> g.set(0));
        timers.values().forEach(Timer::reset);
    }

    /** Returns a formatted summary string for the /legendary report command. */
    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n<gray>--- Plugin Metrics ---\n");
        for (var entry : counters.entrySet()) {
            sb.append("<yellow>").append(entry.getKey()).append("<gray>: <white>").append(entry.getValue().get()).append("\n");
        }
        for (var entry : gauges.entrySet()) {
            sb.append("<aqua>").append(entry.getKey()).append("<gray>: <white>")
                .append(String.format("%.2f", entry.getValue().get())).append("\n");
        }
        for (var entry : timers.entrySet()) {
            sb.append("<gold>").append(entry.getKey()).append("<gray>: <white>")
                .append(String.format("%.2fms", entry.getValue().getAverageMs()))
                .append(" (x").append(entry.getValue().getCount()).append(")\n");
        }
        return sb.toString();
    }
}
