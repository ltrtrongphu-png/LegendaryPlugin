package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.FallingBlock;

import java.util.List;

/**
 * Caps falling-block entities per chunk (sand/gravel/anvil towers, common
 * lag-machine ingredient). Ported from SmartOptimizer.FallingBlockLimiter.
 * Fed a pre-scanned per-chunk list by {@link EntitySweepTask}.
 */
public final class FallingBlockLimiter {

    private final LegendaryPlugin plugin;
    private volatile OptimizationEngine.Level currentLevel = OptimizationEngine.Level.NORMAL;

    public FallingBlockLimiter(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateLevel(OptimizationEngine.Level level) {
        this.currentLevel = level;
    }

    /** Called once per loaded chunk by EntitySweepTask with that chunk's falling blocks already collected. */
    public void enforceInChunk(List<FallingBlock> blocks) {
        if (currentLevel == OptimizationEngine.Level.NORMAL) return;
        int cap = switch (currentLevel) {
            case MILD -> 40;
            case MODERATE -> 20;
            case SEVERE -> 8;
            default -> Integer.MAX_VALUE;
        };
        int excess = blocks.size() - cap;
        for (int i = 0; i < excess; i++) {
            blocks.get(i).remove();
        }
    }
}
