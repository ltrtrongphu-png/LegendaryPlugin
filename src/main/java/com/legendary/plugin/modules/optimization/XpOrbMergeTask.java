package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.ExperienceOrb;

import java.util.List;

/**
 * Merges nearby XP orbs in a chunk into a single orb carrying the summed
 * experience value, exactly like vanilla's own orb-merge but applied more
 * aggressively during lag. Ported from SmartOptimizer.XpOrbMergeTask.
 *
 * See {@link ItemMergeTask}'s class doc for why this takes a pre-scanned
 * list instead of walking chunks itself - {@link EntitySweepTask} owns
 * the single world/chunk/entity scan now.
 */
public final class XpOrbMergeTask {

    private static final int MAX_ORBS_PER_CHUNK = 100;

    private final LegendaryPlugin plugin;
    private volatile OptimizationEngine.Settings current;

    public XpOrbMergeTask(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateSettings(OptimizationEngine.Settings settings) {
        this.current = settings;
    }

    /** Called once per loaded chunk by EntitySweepTask with that chunk's XP orbs already collected. */
    public void mergeInChunk(List<ExperienceOrb> orbs) {
        OptimizationEngine.Settings settings = current;
        if (settings == null || settings.itemMergeRadius <= 0) return;
        double radius = settings.itemMergeRadius;
        if (orbs.size() < 2 || orbs.size() > MAX_ORBS_PER_CHUNK) return;

        for (int i = 0; i < orbs.size(); i++) {
            ExperienceOrb a = orbs.get(i);
            if (a.isDead()) continue;
            for (int j = i + 1; j < orbs.size(); j++) {
                ExperienceOrb b = orbs.get(j);
                if (b.isDead()) continue;
                if (a.getLocation().distanceSquared(b.getLocation()) > radius * radius) continue;
                a.setExperience(a.getExperience() + b.getExperience());
                b.remove();
            }
        }
    }
}
