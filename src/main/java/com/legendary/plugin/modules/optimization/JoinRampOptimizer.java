package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ramps a newly joined player's per-player view distance up gradually
 * instead of instantly loading the full radius, smoothing the chunk-gen
 * spike on login. Ported from SmartOptimizer.JoinRampOptimizer.
 */
public final class JoinRampOptimizer implements Listener {

    private final LegendaryPlugin plugin;
    private WorldOptimizer worldOptimizer;
    private final Map<UUID, BukkitTask> activeRamps = new HashMap<>();
    private final Map<UUID, Long> joinTimes = new HashMap<>();

    public JoinRampOptimizer(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void setWorldOptimizer(WorldOptimizer worldOptimizer) {
        this.worldOptimizer = worldOptimizer;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int target = worldOptimizer != null
            ? worldOptimizer.getBaseViewDistanceMap().getOrDefault(player.getWorld().getName(), player.getWorld().getViewDistance())
            : player.getWorld().getViewDistance();
        int start = Math.max(2, target / 3);
        JoinRampCalculator calculator = new JoinRampCalculator(start, target, 5000L);
        long joinTime = System.currentTimeMillis();
        joinTimes.put(player.getUniqueId(), joinTime);

        try {
            player.setSendViewDistance(start);
        } catch (Throwable ignored) {}

        BukkitTask ramp = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long elapsed = System.currentTimeMillis() - joinTime;
            int current = calculator.distanceAt(elapsed);
            try {
                player.setSendViewDistance(current);
            } catch (Throwable ignored) {}
            if (calculator.isComplete(elapsed)) {
                stopRamp(player.getUniqueId());
            }
        }, 20L, 10L);
        activeRamps.put(player.getUniqueId(), ramp);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopRamp(event.getPlayer().getUniqueId());
        joinTimes.remove(event.getPlayer().getUniqueId());
    }

    private void stopRamp(UUID uuid) {
        BukkitTask task = activeRamps.remove(uuid);
        if (task != null) task.cancel();
    }

    public void shutdown() {
        for (BukkitTask task : activeRamps.values()) task.cancel();
        activeRamps.clear();
        joinTimes.clear();
    }
}
