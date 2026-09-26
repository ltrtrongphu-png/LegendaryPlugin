package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

/**
 * COMPATIBILITY FIX (2026-09-19 merge review, adapted from the DeepSeek
 * LegendaryPlugin v6.0 dump's optimize.optimizers.MythicMobsProtection).
 *
 * Without this, {@link MobCapEnforcer}, {@link ArmorStandLimiter} and
 * {@link ClearEntitiesTask} treat a summoned MythicMobs/ModelEngine boss
 * exactly like any other unnamed monster - a boss with no custom name and
 * no PDC entries set by MythicMobs itself could get deleted mid-fight
 * during a lag spike, which is a real bug in the port before this class:
 * neither {@link ProtectionRules} nor any sweep sub-system previously knew
 * MythicMobs entities existed at all.
 */
public final class MythicMobsProtection {

    private final LegendaryPlugin plugin;
    private volatile boolean mythicMobsLoaded = false;
    private volatile boolean modelEngineLoaded = false;

    public MythicMobsProtection(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        Plugin mythicMobs = Bukkit.getPluginManager().getPlugin("MythicMobs");
        mythicMobsLoaded = mythicMobs != null && mythicMobs.isEnabled();
        Plugin modelEngine = Bukkit.getPluginManager().getPlugin("ModelEngine");
        modelEngineLoaded = modelEngine != null && modelEngine.isEnabled();
        if (mythicMobsLoaded) plugin.getLogger().info("MythicMobs detected - boss/summon protection enabled for optimization cleanup tasks.");
        if (modelEngineLoaded) plugin.getLogger().info("ModelEngine detected - modeled-entity protection enabled for optimization cleanup tasks.");
    }

    public boolean isActive() {
        return mythicMobsLoaded || modelEngineLoaded;
    }

    /**
     * Best-effort "don't touch this" heuristic without a hard compile
     * dependency on the MythicMobs API (which would force every server
     * admin to install MythicMobs even if they don't use it). Checks:
     * absurdly high max health (MythicMobs bosses routinely set 500-100000+
     * HP, ordinary vanilla mobs never exceed a few dozen), a name that
     * looks boss-like, or active leash/tame state that already marks it
     * protected via {@link ProtectionRules} anyway.
     */
    public boolean shouldProtect(Entity entity) {
        if (!isActive()) return false;
        if (!(entity instanceof LivingEntity living)) return false;
        if (living.getMaxHealth() >= 500.0) return true;
        if (living.customName() != null) {
            String name = PlainTextComponentSerializer.plainText().serialize(living.customName()).toLowerCase();
            if (name.contains("boss") || name.contains("elite") || name.contains("[mm]")) return true;
        }
        return false;
    }
}
