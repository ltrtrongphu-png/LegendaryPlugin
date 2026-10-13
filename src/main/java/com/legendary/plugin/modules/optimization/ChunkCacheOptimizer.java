package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proactively unloads chunks that are loaded but have no players nearby
 * and no entities/tile entities. Runs on a separate timer from the main
 * optimization tick so it can run even when TPS is healthy (preventive
 * memory management rather than reactive).
 *
 * Complements the SEVERE-lag-only chunk unloads in OptimizationModule
 * by doing a gentler, regular cleanup at all TPS levels.
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class ChunkCacheOptimizer {

    private final LegendaryPlugin plugin;
    private BukkitTask task;
    private long intervalSeconds = 120;
    private int maxUnloadPerCycle = 20;
    private int minChunkAgeSeconds = 60;
    private boolean enabled = false;

    public ChunkCacheOptimizer(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        var cfg = plugin.getConfig();
        intervalSeconds = cfg.getLong("optimization.chunk-cache.interval-seconds", 120);
        maxUnloadPerCycle = cfg.getInt("optimization.chunk-cache.max-unload-per-cycle", 20);
        minChunkAgeSeconds = cfg.getInt("optimization.chunk-cache.min-chunk-age-seconds", 60);
    }

    public void start() {
        if (task != null) task.cancel();
        long intervalTicks = Math.max(20L, intervalSeconds * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::clean, intervalTicks, intervalTicks);
        enabled = true;
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        enabled = false;
    }

    private void clean() {
        AtomicInteger unloaded = new AtomicInteger();
        long now = System.currentTimeMillis();
        long minAgeMs = minChunkAgeSeconds * 1000L;
        int renderDistSq = 128 * 128;

        for (World world : Bukkit.getWorlds()) {
            if (unloaded.get() >= maxUnloadPerCycle) break;
            var players = world.getPlayers();
            for (var chunk : world.getLoadedChunks()) {
                if (unloaded.get() >= maxUnloadPerCycle) break;

                boolean isEmpty = chunk.getEntities().length == 0
                    && chunk.getTileEntities().length == 0;
                if (!isEmpty) continue;

                boolean nearPlayer = false;
                int cx = chunk.getX() << 4;
                int cz = chunk.getZ() << 4;
                for (var p : players) {
                    int dx = p.getLocation().getBlockX() - cx;
                    int dz = p.getLocation().getBlockZ() - cz;
                    if (dx * dx + dz * dz < renderDistSq) { nearPlayer = true; break; }
                }
                if (nearPlayer) continue;

                if (world.unloadChunk(chunk)) {
                    unloaded.incrementAndGet();
                }
            }
        }
        if (unloaded.get() > 0) {
            plugin.getMetrics().counter("optimization.chunks.proactively-unloaded").add(unloaded.get());
        }
    }

    public boolean isEnabled() { return enabled; }
}
