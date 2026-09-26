package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.CoordCodec;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REWORKED 2026-09-20 per user feedback: the original version counted
 * redstone-signal-change events per CHUNK in a 1-second window and, once
 * over the limit, completely FROZE every further change in that chunk
 * until the window reset - a hard on/off cutoff that could make an
 * entire chunk's redstone (including unrelated, legitimate circuits
 * sharing that chunk) suddenly stop working, and felt broken rather than
 * "slower". It also conflated every redstone component in a chunk into
 * one shared budget, so a normal farm with many small updates could
 * trip the same limit as one abusive fast clock.
 *
 * This version instead enforces a minimum interval between allowed
 * updates at each individual block position (e.g. a specific lever,
 * repeater, or clock component), derived from a speed multiplier - e.g.
 * speed-multiplier: 0.65 means every redstone position effectively runs
 * at ~65% of its normal update rate, smoothly, rather than stopping
 * outright. A block that isn't toggling fast to begin with is never
 * affected (there's nothing to throttle), so ordinary builds are left
 * alone; only components updating faster than the throttle allows
 * (redstone-clock-based lag machines being the main real-world case)
 * are slowed down - and only that specific component, not everything
 * else sharing its chunk.
 */
public final class RedstoneLimiterListener implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<Long, Long> lastAllowedChangeMs = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile long minIntervalMs;
    private long lastCleanup = 0;

    private static final long NORMAL_TICK_MS = 50; // 1 vanilla tick at 20 TPS

    public RedstoneLimiterListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
        updateInterval(0.65);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("optimization.modules.redstone-limiter", true);
        double speedMultiplier = plugin.getConfig().getDouble("optimization.redstone-limiter.speed-multiplier", 0.65);
        updateInterval(speedMultiplier);
    }

    private void updateInterval(double speedMultiplier) {
        double clamped = Math.max(0.05, Math.min(1.0, speedMultiplier));
        this.minIntervalMs = Math.max(1, Math.round(NORMAL_TICK_MS / clamped));
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        if (!enabled) return;
        var block = event.getBlock();
        long key = CoordCodec.pack(block.getX(), block.getY(), block.getZ());
        long now = System.currentTimeMillis();
        Long last = lastAllowedChangeMs.get(key);
        if (last != null && now - last < minIntervalMs) {
            // Hold the signal at its previous value - this position is updating faster than
            // its throttled rate allows, so this particular change is skipped (not the whole
            // chunk's redstone, just this one component).
            event.setNewCurrent(event.getOldCurrent());
        } else {
            lastAllowedChangeMs.put(key, now);
        }

        if (now - lastCleanup > 30000) {
            long cutoff = now - 60000;
            lastAllowedChangeMs.entrySet().removeIf(e -> e.getValue() < cutoff);
            lastCleanup = now;
        }
    }
}
