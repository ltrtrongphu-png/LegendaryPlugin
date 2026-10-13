package com.legendary.plugin.modules.optimization;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.TileState;

/**
 * Limits tile entities (hoppers, furnaces, dispensers, etc.) per chunk
 * during lag to reduce tick overhead. Integrated into EntitySweepTask's
 * combined scan to avoid a separate world iteration.
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class TileEntityLimiter {

    private int maxPerChunkNormal = 256;
    private int maxPerChunkLag = 128;
    private int maxPerChunkSevere = 64;
    private volatile OptimizationEngine.Level currentLevel = OptimizationEngine.Level.NORMAL;

    public void updateLevel(OptimizationEngine.Level level) {
        this.currentLevel = level;
    }

    public int getMaxPerChunk() {
        return switch (currentLevel) {
            case NORMAL -> maxPerChunkNormal;
            case MILD -> Math.max(64, maxPerChunkNormal * 3 / 4);
            case MODERATE -> maxPerChunkLag;
            case SEVERE -> maxPerChunkSevere;
        };
    }

    public void loadConfigValues(com.legendary.plugin.LegendaryPlugin plugin) {
        var cfg = plugin.getConfig();
        maxPerChunkNormal = cfg.getInt("optimization.tile-entity-limiter.max-per-chunk-normal", 256);
        maxPerChunkLag = cfg.getInt("optimization.tile-entity-limiter.max-per-chunk-lag", 128);
        maxPerChunkSevere = cfg.getInt("optimization.tile-entity-limiter.max-per-chunk-severe", 64);
    }

    /**
     * Reports how many chunks exceed the tile-entity cap. Does NOT remove
     * tile entities (that would destroy player builds) — instead returns
     * the count for logging/advisory purposes so admins can act manually.
     */
    public int scanOvercapChunks(World world) {
        int max = getMaxPerChunk();
        int overcap = 0;
        for (Chunk chunk : world.getLoadedChunks()) {
            int tileCount = 0;
            for (var blockState : chunk.getTileEntities()) {
                if (blockState instanceof TileState) tileCount++;
            }
            if (tileCount > max) overcap++;
        }
        return overcap;
    }
}
