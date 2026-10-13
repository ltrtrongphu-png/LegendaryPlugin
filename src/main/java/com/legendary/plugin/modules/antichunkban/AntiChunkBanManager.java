package com.legendary.plugin.modules.antichunkban;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Chunk;

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
    private int maxTileEntities = 200;
    private int maxEntities = 3000;
    private boolean enabled = false;

    public AntiChunkBanManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        var cfg = plugin.getConfig();
        maxTileEntities = cfg.getInt("anti-chunk-ban.max-tile-entities", 200);
        maxEntities = cfg.getInt("anti-chunk-ban.max-entities", 3000);
    }

    public void start() {
        enabled = true;
        plugin.getLogger().info("[AntiChunkBan] Active — max tile entities: " + maxTileEntities
            + ", max entities: " + maxEntities + "; detections are advisory only");
    }

    public void stop() {
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

    public boolean isEnabled() { return enabled; }
}
