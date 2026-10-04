package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies / restores per-world view- and simulation-distance based on the
 * current {@link OptimizationEngine.Settings}. Ported from
 * SmartOptimizer.WorldOptimizer.
 */
public final class WorldOptimizer {

    private final LegendaryPlugin plugin;
    private final Map<String, Integer> baseViewDistance = new HashMap<>();
    private final Map<String, Integer> baseSimDistance = new HashMap<>();

    public WorldOptimizer(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void detectBaseDistancesIfNeeded() {
        for (World world : plugin.getServer().getWorlds()) {
            baseViewDistance.putIfAbsent(world.getName(), world.getViewDistance());
            baseSimDistance.putIfAbsent(world.getName(), world.getSimulationDistance());
        }
    }

    public void apply(OptimizationEngine.Settings settings) {
        for (World world : plugin.getServer().getWorlds()) {
            int baseView = baseViewDistance.getOrDefault(world.getName(), world.getViewDistance());
            int baseSim = baseSimDistance.getOrDefault(world.getName(), world.getSimulationDistance());
            // Clamp right before the Bukkit call too (belt-and-braces on top of
            // OptimizationEngine's own clamp) - this exact call is what threw
            // "View/Simulation distance -1 is out of range of [2, 32]" in production before
            // the root cause (an unresolved -1 "auto" sentinel) was fixed upstream.
            world.setViewDistance(clampDistance(Math.min(baseView, settings.viewDistance)));
            world.setSimulationDistance(clampDistance(Math.min(baseSim, settings.simulationDistance)));
        }
    }

    private static int clampDistance(int distance) {
        return Math.max(2, Math.min(32, distance));
    }

    public void restoreAllToBase() {
        for (World world : plugin.getServer().getWorlds()) {
            Integer view = baseViewDistance.get(world.getName());
            Integer sim = baseSimDistance.get(world.getName());
            if (view != null) world.setViewDistance(view);
            if (sim != null) world.setSimulationDistance(sim);
        }
    }

    public Map<String, Integer> getBaseViewDistanceMap() { return baseViewDistance; }
    public Map<String, Integer> getBaseSimDistanceMap() { return baseSimDistance; }
}
