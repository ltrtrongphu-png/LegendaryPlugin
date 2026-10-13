package com.legendary.plugin.modules.optimization;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

import java.util.logging.Logger;

/**
 * Disables AI for non-priority mobs beyond a configurable radius from
 * any online player during lag. This dramatically cuts CPU usage when the
 * server is under stress, since mob AI pathfinding is one of the most
 * expensive per-tick costs. Priority entities (named, leashed, tamed,
 * MythicMobs bosses) are always left alone.
 *
 * Uses Paper's Mob#setAware API (available since Paper 1.13+). On servers
 * without that API, silently no-ops.
 */
public final class EntityAiSkipManager {

    private static final Logger LOGGER = Bukkit.getServer() != null
        ? Bukkit.getServer().getLogger() : null;

    private volatile OptimizationEngine.Settings current;
    private boolean apiAvailable = true;
    private volatile boolean dirty;

    public void updateSettings(OptimizationEngine.Settings settings) {
        this.current = settings;
    }

    public void apply() {
        OptimizationEngine.Settings settings = current;
        if (settings == null) return;
        if (settings.level == OptimizationEngine.Level.NORMAL || settings.entityAiSkipRadius <= 0) {
            if (dirty) restoreAll();
            return;
        }

        int radius = settings.entityAiSkipRadius;
        double radiusSq = radius * radius;

        for (World world : Bukkit.getWorlds()) {
            for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                if (isPriority(mob)) continue;

                boolean nearPlayer = false;
                for (var player : world.getPlayers()) {
                    if (mob.getLocation().distanceSquared(player.getLocation()) < radiusSq) {
                        nearPlayer = true;
                        break;
                    }
                }

                if (!nearPlayer) dirty = true;
                setAware(mob, nearPlayer);
            }
        }
    }

    public void restoreAll() {
        if (!apiAvailable || !dirty) return;
        for (World world : Bukkit.getWorlds()) {
            for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                if (isPriority(mob)) continue;
                setAware(mob, true);
            }
        }
        dirty = false;
    }

    private void setAware(Mob mob, boolean aware) {
        if (!apiAvailable) return;
        try {
            mob.setAware(aware);
        } catch (NoSuchMethodError | AbstractMethodError e) {
            apiAvailable = false;
        } catch (Throwable ignored) {
        }
    }

    private boolean isPriority(LivingEntity entity) {
        return ProtectionRules.isProtectedMob(entity);
    }
}
