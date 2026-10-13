package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM heap-pressure monitor. Polls heap usage every N seconds and fires
 * a callback when memory crosses the warning threshold. The optimization
 * module uses this to aggressively reduce view distance, skip entity AI,
 * and trigger emergency chunk unloads before the GC pressure causes TPS
 * drops — preventing the death-spiral where low TPS causes more object
 * accumulation which causes more GC pauses which causes lower TPS.
 */
public final class MemoryPressureMonitor {

    private final LegendaryPlugin plugin;
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private BukkitTask task;

    private boolean enabled;
    private int pollIntervalTicks;
    private double warningThresholdPercent;
    private double criticalThresholdPercent;
    private long maxHeapBytes;

    private final AtomicBoolean warningActive = new AtomicBoolean(false);
    private final AtomicBoolean criticalActive = new AtomicBoolean(false);

    private Runnable onWarning;
    private Runnable onCritical;
    private Runnable onRecovery;

    public MemoryPressureMonitor(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("optimization.memory-pressure.enabled", true);
        pollIntervalTicks = plugin.getConfig().getInt("optimization.memory-pressure.poll-interval-seconds", 5) * 20;
        warningThresholdPercent = plugin.getConfig().getDouble("optimization.memory-pressure.warning-threshold-percent", 75.0);
        criticalThresholdPercent = plugin.getConfig().getDouble("optimization.memory-pressure.critical-threshold-percent", 90.0);
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        maxHeapBytes = heap.getMax() > 0 ? heap.getMax() : Runtime.getRuntime().maxMemory();
    }

    public void start() {
        if (!enabled) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, pollIntervalTicks, pollIntervalTicks);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        warningActive.set(false);
        criticalActive.set(false);
    }

    public void setCallbacks(Runnable onWarning, Runnable onCritical, Runnable onRecovery) {
        this.onWarning = onWarning;
        this.onCritical = onCritical;
        this.onRecovery = onRecovery;
    }

    public double getHeapUsagePercent() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        if (maxHeapBytes <= 0) return 0.0;
        return (heap.getUsed() / (double) maxHeapBytes) * 100.0;
    }

    public boolean isWarning() { return warningActive.get(); }
    public boolean isCritical() { return criticalActive.get(); }

    private void poll() {
        double usage = getHeapUsagePercent();
        if (usage >= criticalThresholdPercent) {
            if (criticalActive.compareAndSet(false, true)) {
                plugin.getLogger().warning("[MemoryPressure] CRITICAL: heap usage " + String.format("%.1f%%", usage)
                    + " exceeds " + criticalThresholdPercent + "% — triggering emergency optimization.");
                if (onCritical != null) onCritical.run();
            }
            warningActive.set(true);
        } else if (usage >= warningThresholdPercent) {
            criticalActive.set(false);
            if (warningActive.compareAndSet(false, true)) {
                plugin.getLogger().warning("[MemoryPressure] WARNING: heap usage " + String.format("%.1f%%", usage)
                    + " exceeds " + warningThresholdPercent + "%.");
                if (onWarning != null) onWarning.run();
            }
        } else {
            boolean wasWarning = warningActive.getAndSet(false);
            boolean wasCritical = criticalActive.getAndSet(false);
            if (wasWarning || wasCritical) {
                plugin.getLogger().info("[MemoryPressure] RECOVERED: heap usage " + String.format("%.1f%%", usage) + ".");
                if (onRecovery != null) onRecovery.run();
            }
        }
    }
}
