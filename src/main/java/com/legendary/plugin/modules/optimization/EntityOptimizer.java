package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.World;
import org.bukkit.entity.SpawnCategory;

import java.util.HashMap;
import java.util.Map;

/**
 * Scales each world's per-category mob spawn limits by the current
 * OptimizationEngine multiplier. Ported from SmartOptimizer.EntityOptimizer.
 */
public final class EntityOptimizer {

    private static final SpawnCategory[] CATEGORIES = {
        SpawnCategory.MONSTER, SpawnCategory.ANIMAL, SpawnCategory.WATER_ANIMAL,
        SpawnCategory.AMBIENT, SpawnCategory.AXOLOTL
    };

    private final LegendaryPlugin plugin;
    private final Map<String, Map<SpawnCategory, Integer>> baseLimits = new HashMap<>();

    public EntityOptimizer(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private void ensureBaseCaptured(World world) {
        baseLimits.computeIfAbsent(world.getName(), k -> {
            Map<SpawnCategory, Integer> map = new HashMap<>();
            for (SpawnCategory category : CATEGORIES) {
                try {
                    map.put(category, world.getSpawnLimit(category));
                } catch (Exception ignored) {}
            }
            return map;
        });
    }

    public void recaptureAll() {
        for (World world : plugin.getServer().getWorlds()) ensureBaseCaptured(world);
    }

    public void apply(OptimizationEngine.Settings settings) {
        for (World world : plugin.getServer().getWorlds()) {
            ensureBaseCaptured(world);
            Map<SpawnCategory, Integer> base = baseLimits.get(world.getName());
            for (var entry : base.entrySet()) {
                int scaled = Math.max(0, (int) Math.round(entry.getValue() * settings.mobSpawnMultiplier));
                try {
                    world.setSpawnLimit(entry.getKey(), scaled);
                } catch (Exception ignored) {}
            }
        }
    }

    public void restoreAllToBase() {
        for (var worldEntry : baseLimits.entrySet()) {
            World world = plugin.getServer().getWorld(worldEntry.getKey());
            if (world == null) continue;
            for (var entry : worldEntry.getValue().entrySet()) {
                try {
                    world.setSpawnLimit(entry.getKey(), entry.getValue());
                } catch (Exception ignored) {}
            }
        }
    }
}
