package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.ArmorStand;

import java.util.List;

/**
 * Caps armor-stand entities per chunk (a very common decorative/farm-spam
 * lag source). Named or otherwise interacted-with stands are left alone.
 * Ported from SmartOptimizer.ArmorStandLimiter. Fed a pre-scanned
 * per-chunk list by {@link EntitySweepTask}.
 */
public final class ArmorStandLimiter {

    private final LegendaryPlugin plugin;
    private volatile OptimizationEngine.Level currentLevel = OptimizationEngine.Level.NORMAL;

    public ArmorStandLimiter(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateLevel(OptimizationEngine.Level level) {
        this.currentLevel = level;
    }

    /** Called once per loaded chunk by EntitySweepTask with that chunk's unprotected armor stands already collected. */
    public void enforceInChunk(List<ArmorStand> stands) {
        if (currentLevel == OptimizationEngine.Level.NORMAL) return;
        int cap = switch (currentLevel) {
            case MILD -> 60;
            case MODERATE -> 30;
            case SEVERE -> 15;
            default -> Integer.MAX_VALUE;
        };
        int excess = stands.size() - cap;
        for (int i = 0; i < excess; i++) {
            stands.get(i).remove();
        }
    }

    public boolean isUnprotected(ArmorStand stand) {
        return stand.getCustomName() == null && stand.getPersistentDataContainer().isEmpty();
    }
}
