package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tracks recent TPS to compute a "compensation factor" so movement/timer
 * checks can relax their thresholds automatically when the whole server
 * is lagging (avoiding false positives during lag spikes, not just for
 * the cheater but for every player). Ported from BaB.TpsMonitor.
 */
public final class TpsMonitor {

    private final LegendaryPlugin plugin;
    private boolean compensationEnabled;
    private double minTps;
    private double maxFactor;
    private boolean adaptiveSensitivityEnabled;
    private double sensitivityFloor;
    private double sensitivityCutoffTps;
    private double lagSuppressTps;
    private BukkitTask task;
    private volatile double currentTps = 20.0;

    public TpsMonitor(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        compensationEnabled = plugin.getConfig().getBoolean("anticheat.tps-compensation.enabled", true);
        minTps = plugin.getConfig().getDouble("anticheat.tps-compensation.min-tps", 15.0);
        maxFactor = plugin.getConfig().getDouble("anticheat.tps-compensation.max-factor", 1.5);
        adaptiveSensitivityEnabled = plugin.getConfig().getBoolean("anticheat.tps-compensation.adaptive-sensitivity.enabled", true);
        sensitivityFloor = plugin.getConfig().getDouble("anticheat.tps-compensation.adaptive-sensitivity.floor", 0.3);
        sensitivityCutoffTps = plugin.getConfig().getDouble("anticheat.tps-compensation.adaptive-sensitivity.cutoff-tps", 14.0);
        lagSuppressTps = plugin.getConfig().getDouble("anticheat.tps-compensation.lag-suppress-tps", 10.0);
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                double[] tps = Bukkit.getTPS();
                currentTps = tps.length > 0 ? Math.min(20.0, tps[0]) : 20.0;
            } catch (Throwable ignored) {}
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    public double getTps() { return currentTps; }

    public double getCompensationFactor() {
        if (!compensationEnabled || currentTps >= 19.5) return 1.0;
        if (currentTps <= minTps) return maxFactor;
        double ratio = (19.5 - currentTps) / (19.5 - minTps);
        return 1.0 + ratio * (maxFactor - 1.0);
    }

    /**
     * Returns a global flag-weight multiplier (0.0-1.0) that scales down
     * all violation weights when TPS drops. At 20 TPS the multiplier is
     * 1.0 (no reduction). It linearly decreases to {@code sensitivityFloor}
     * at {@code sensitivityCutoffTps}. Below the cutoff, it stays at the
     * floor. This prevents false-positive floods during lag spikes.
     */
    public double getSensitivityMultiplier() {
        if (!adaptiveSensitivityEnabled || currentTps >= 19.5) return 1.0;
        if (currentTps <= sensitivityCutoffTps) return sensitivityFloor;
        double ratio = (19.5 - currentTps) / (19.5 - sensitivityCutoffTps);
        return 1.0 - ratio * (1.0 - sensitivityFloor);
    }

    /**
     * Returns true when TPS is critically low and the server is in a
     * lag-spike state. The threat-action system pauses automatic kicks
     * and bans while this is true to avoid punishing players for
     * lag-induced false positives.
     */
    public boolean isLagSpike() {
        return currentTps <= lagSuppressTps;
    }
}
