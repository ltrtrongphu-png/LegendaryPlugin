package com.legendary.plugin.modules.optimization;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

/**
 * Central "is this entity/item safe to auto-clear or auto-cap" rule set,
 * shared by ItemMergeTask, MobCapEnforcer, ClearEntitiesTask, etc.
 * Ported from SmartOptimizer.ProtectionRules.
 */
public final class ProtectionRules {

    private static volatile MythicMobsProtection mythicMobsProtection;

    /** Wired once from OptimizationModule.onEnable() so isProtectedMob() can consult it. */
    public static void setMythicMobsProtection(MythicMobsProtection instance) {
        mythicMobsProtection = instance;
    }

    public static final class ItemFlags {
        public boolean hasDisplayName;
        public boolean hasLore;
        public boolean hasEnchants;
        public boolean hasItemPdc;
    }

    public static final class MobFlags {
        public boolean hasCustomName;
        public boolean hasPdc;
        public boolean isLeashed;
        public boolean isTamed;
        public boolean hasPassenger;
        public boolean isFromSpawner;
    }

    private ProtectionRules() {}

    public static ItemFlags analyze(ItemStack stack) {
        ItemFlags flags = new ItemFlags();
        if (stack == null) return flags;
        var meta = stack.getItemMeta();
        if (meta != null) {
            flags.hasDisplayName = meta.hasDisplayName();
            flags.hasLore = meta.hasLore();
            flags.hasEnchants = meta.hasEnchants();
            flags.hasItemPdc = !meta.getPersistentDataContainer().isEmpty();
        }
        return flags;
    }

    public static boolean isProtectedItem(Item entity, boolean skipNamedOrEnchanted) {
        if (!skipNamedOrEnchanted) return false;
        ItemFlags flags = analyze(entity.getItemStack());
        return flags.hasDisplayName || flags.hasLore || flags.hasEnchants || flags.hasItemPdc
            || !entity.getPersistentDataContainer().isEmpty();
    }

    public static MobFlags analyze(LivingEntity entity) {
        MobFlags flags = new MobFlags();
        flags.hasCustomName = entity.getCustomName() != null;
        flags.hasPdc = !entity.getPersistentDataContainer().isEmpty();
        try { flags.isLeashed = entity.isLeashed(); } catch (IllegalStateException ignored) { flags.isLeashed = false; }
        flags.hasPassenger = !entity.getPassengers().isEmpty();
        if (entity instanceof org.bukkit.entity.Tameable tameable) {
            flags.isTamed = tameable.isTamed();
        }
        return flags;
    }

    public static boolean isProtectedMob(LivingEntity entity) {
        MobFlags flags = analyze(entity);
        if (flags.hasCustomName || flags.hasPdc || flags.isLeashed || flags.isTamed || flags.hasPassenger) return true;
        return mythicMobsProtection != null && mythicMobsProtection.shouldProtect(entity);
    }

    public static boolean isProtectedEntity(Entity entity) {
        if (entity instanceof Item item) return isProtectedItem(item, true);
        if (entity instanceof LivingEntity living) return isProtectedMob(living);
        return false;
    }
}
