package com.legendary.plugin.modules.anticheat;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Low-overhead measurements for the shared check-state gate. */
public final class PerfProfiler {

    private final Map<String, Measurement> measurements = new ConcurrentHashMap<>();

    public void record(String checkId, long elapsedNanos) {
        Measurement measurement = measurements.computeIfAbsent(checkId, ignored -> new Measurement());
        measurement.calls.increment();
        measurement.totalNanos.add(Math.max(0L, elapsedNanos));
        measurement.maxNanos.accumulateAndGet(elapsedNanos, Math::max);
    }

    public java.util.List<Snapshot> snapshots() {
        return measurements.entrySet().stream()
            .map(entry -> new Snapshot(entry.getKey(), entry.getValue().calls.sum(),
                entry.getValue().totalNanos.sum(), entry.getValue().maxNanos.get()))
            .sorted(Comparator.comparingLong(Snapshot::averageNanos).reversed())
            .toList();
    }

    private static final class Measurement {
        private final LongAdder calls = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final java.util.concurrent.atomic.AtomicLong maxNanos =
            new java.util.concurrent.atomic.AtomicLong();
    }

    public record Snapshot(String checkId, long calls, long totalNanos, long maxNanos) {
        public long averageNanos() {
            return calls == 0 ? 0 : totalNanos / calls;
        }
    }
}
