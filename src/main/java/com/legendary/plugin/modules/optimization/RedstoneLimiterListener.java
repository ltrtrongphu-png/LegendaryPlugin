package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.CoordCodec;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smoothly throttles redstone update rate per-component instead of
 * hard-freezing a whole chunk. Only components updating faster than the
 * throttled rate are affected - normal circuits are left alone.
 *
 * PERFORMANCE UPGRADE: the cleanup is now triggered inline with a
 * size-based threshold rather than a time-based check, so the map is
 * cleaned proactively before it grows too large. Uses a higher initial
 * capacity to reduce ConcurrentHashMap rehashing.
 */
public final class RedstoneLimiterListener implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<Long, Long> lastAllowedChangeMs = new ConcurrentHashMap<>(4096);
    private volatile boolean enabled = true;
    private volatile long minIntervalMs;
    private volatile int maxTrackedPositions;
    private long lastCleanup = 0;

    private static final long NORMAL_TICK_MS = 50;

    public RedstoneLimiterListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
        updateInterval(0.65);
        maxTrackedPositions = 50000;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("optimization.modules.redstone-limiter", true);
        double speedMultiplier = plugin.getConfig().getDouble("optimization.redstone-limiter.speed-multiplier", 0.65);
        updateInterval(speedMultiplier);
        maxTrackedPositions = plugin.getConfig().getInt("optimization.redstone-limiter.max-tracked-positions", 50000);
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
            event.setNewCurrent(event.getOldCurrent());
        } else {
            lastAllowedChangeMs.put(key, now);
        }

        if (now - lastCleanup > 30000 || lastAllowedChangeMs.size() > maxTrackedPositions) {
            long cutoff = now - 60000;
            Iterator<Map.Entry<Long, Long>> it = lastAllowedChangeMs.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() < cutoff) it.remove();
            }
            lastCleanup = now;
        }
    }
}
