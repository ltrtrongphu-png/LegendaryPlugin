package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;

import java.util.List;

/**
 * Hard-caps the number of unprotected monsters per chunk once the server
 * is under stress, removing the oldest/least-relevant excess mobs first.
 * Ported from SmartOptimizer.MobCapEnforcer. Fed a pre-scanned per-chunk
 * list by {@link EntitySweepTask} - see {@link ItemMergeTask}'s class doc.
 */
public final class MobCapEnforcer {

    private final LegendaryPlugin plugin;
    private volatile OptimizationEngine.Level currentLevel = OptimizationEngine.Level.NORMAL;

    public MobCapEnforcer(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateLevel(OptimizationEngine.Level level) {
        this.currentLevel = level;
    }

    /** Called once per loaded chunk by EntitySweepTask with that chunk's unprotected monsters already collected. */
    public void enforceInChunk(List<Monster> monsters) {
        if (currentLevel == OptimizationEngine.Level.NORMAL) return;
        int cap = switch (currentLevel) {
            case MILD -> 24;
            case MODERATE -> 16;
            case SEVERE -> 8;
            default -> Integer.MAX_VALUE;
        };
        int excess = monsters.size() - cap;
        for (int i = 0; i < excess; i++) {
            monsters.get(i).remove();
        }
    }

    public boolean isProtected(LivingEntity entity) {
        return ProtectionRules.isProtectedMob(entity);
    }
}
