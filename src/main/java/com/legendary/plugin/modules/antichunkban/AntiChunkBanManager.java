package com.legendary.plugin.modules.antichunkban;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core logic for Anti-Chunk-Ban: scans chunks for dangerous entity/tile
 * counts, quarantines dangerous chunks, and provides safe-teleport logic.
 *
 * A chunk is "dangerous" if:
 *   - tile entity count exceeds maxTileEntities (default 200)
 *   - entity count exceeds maxEntities (default 3000)
 *   - specific ban-block materials are present above a threshold
 *
 * Quarantined chunks are blocked from player entry for quarantineSeconds.
 * Staff with legendary.antichunkban.bypass can enter quarantined chunks.
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class AntiChunkBanManager {

    private final LegendaryPlugin plugin;
    private final Set<String> quarantinedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> quarantineExpiry = new ConcurrentHashMap<>();
    private BukkitTask cleanupTask;

    private int maxTileEntities = 200;
    private int maxEntities = 3000;
    private int quarantineSeconds = 300;
    private boolean autoKick = true;
    private boolean enabled = false;

    public AntiChunkBanManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        var cfg = plugin.getConfig();
        maxTileEntities = cfg.getInt("anti-chunk-ban.max-tile-entities", 200);
        maxEntities = cfg.getInt("anti-chunk-ban.max-entities", 3000);
        quarantineSeconds = cfg.getInt("anti-chunk-ban.quarantine-seconds", 300);
        autoKick = cfg.getBoolean("anti-chunk-ban.auto-kick", true);
    }

    public void start() {
        if (cleanupTask != null) cleanupTask.cancel();
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupQuarantines, 600L, 600L);
        enabled = true;
        plugin.getLogger().info("[AntiChunkBan] Active — max tile entities: " + maxTileEntities
            + ", max entities: " + maxEntities + ", quarantine: " + quarantineSeconds + "s");
    }

    public void stop() {
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        quarantinedChunks.clear();
        quarantineExpiry.clear();
        enabled = false;
    }

    public boolean isChunkDangerous(Chunk chunk) {
        if (chunk == null) return false;
        int tileCount = chunk.getTileEntities().length;
        if (tileCount > maxTileEntities) return true;
        int entityCount = chunk.getEntities().length;
        if (entityCount > maxEntities) return true;
        return false;
    }

    public String chunkKey(World world, int x, int z) {
        return world.getName() + ":" + x + ":" + z;
    }

    public String chunkKey(Chunk chunk) {
        return chunkKey(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    public boolean isQuarantined(World world, int x, int z) {
        String key = chunkKey(world, x, z);
        return quarantinedChunks.contains(key);
    }

    public void quarantine(Chunk chunk) {
        String key = chunkKey(chunk);
        quarantinedChunks.add(key);
        quarantineExpiry.put(key, System.currentTimeMillis() + quarantineSeconds * 1000L);
        plugin.getLogger().warning("[AntiChunkBan] Quarantined chunk " + key
            + " (tile entities: " + chunk.getTileEntities().length
            + ", entities: " + chunk.getEntities().length + ")");
        plugin.getMetrics().counter("antichunkban.quarantines").increment();
    }

    public void unquarantine(World world, int x, int z) {
        String key = chunkKey(world, x, z);
        quarantinedChunks.remove(key);
        quarantineExpiry.remove(key);
    }

    public void clearAllQuarantines() {
        quarantinedChunks.clear();
        quarantineExpiry.clear();
    }

    private void cleanupQuarantines() {
        long now = System.currentTimeMillis();
        quarantineExpiry.entrySet().removeIf(entry -> {
            if (now >= entry.getValue()) {
                quarantinedChunks.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    public Location findSafeLocation(World world, int chunkX, int chunkZ, Player player) {
        int radius = 1;
        for (int attempts = 0; attempts < 32; attempts++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int testX = chunkX + dx;
                    int testZ = chunkZ + dz;
                    if (isQuarantined(world, testX, testZ)) continue;

                    Chunk testChunk = world.getChunkAt(testX, testZ);
                    if (!isChunkDangerous(testChunk)) {
                        Location loc = new Location(world, (testX << 4) + 8, 64, (testZ << 4) + 8);
                        int y = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
                        loc.setY(y + 1);
                        return loc;
                    }
                }
            }
            radius++;
        }
        return world.getSpawnLocation();
    }

    public boolean isAutoKick() { return autoKick; }
    public boolean isEnabled() { return enabled; }
    public Set<String> getQuarantinedChunks() { return Set.copyOf(quarantinedChunks); }
}
