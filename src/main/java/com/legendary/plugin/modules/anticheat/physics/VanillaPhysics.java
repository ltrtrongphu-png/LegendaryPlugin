package com.legendary.plugin.modules.anticheat.physics;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * NEW FEATURE (2026-09-19 merge, scoped-down from the DeepSeek dump's
 * physics.VanillaPhysics). This is deliberately NOT a full vanilla
 * movement-simulation engine (that is a multi-week undertaking done
 * properly - see README "Known simplifications") - it covers the one
 * concrete, common bug this port had: {@code MovementCheck} compared
 * every player's speed against one fixed cap with no idea that Speed,
 * Slowness or Jump Boost potion effects legitimately change how fast a
 * player can move or how high they can jump. Without this, a player
 * drinking a Speed II potion would have been flagged by MovementCheck
 * for simply using a vanilla game mechanic.
 *
 * Multipliers approximate vanilla's real formula (each effect level adds
 * ~20% movement speed / ~depends for jump) closely enough to avoid false
 * positives without trying to be pixel-perfect - being slightly generous
 * here is the correct trade-off for an anti-cheat cap (better to allow a
 * borderline-legitimate move than flag a real potion user).
 */
public final class VanillaPhysics {

    private VanillaPhysics() {}

    /** Multiply the check's base horizontal-speed cap by this before comparing. */
    public static double horizontalSpeedMultiplier(Player player) {
        double multiplier = 1.0;
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) multiplier *= 1.0 + 0.20 * (speed.getAmplifier() + 1);
        PotionEffect slow = player.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slow != null) multiplier *= Math.max(0.0, 1.0 - 0.15 * (slow.getAmplifier() + 1));

        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) multiplier *= 2.0;
        multiplier *= soulSpeedMultiplier(player);
        multiplier *= depthStriderMultiplier(player);
        multiplier *= swiftSneakMultiplier(player);
        multiplier *= iceSpeedMultiplier(player);
        return multiplier;
    }

    private static double soulSpeedMultiplier(Player player) {
        var boots = player.getInventory().getBoots();
        if (boots == null || boots.getType() == org.bukkit.Material.AIR) return 1.0;
        int level = boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SOUL_SPEED);
        if (level <= 0) return 1.0;
        var below = player.getLocation().clone().subtract(0, 1, 0).getBlock().getType();
        if (below == org.bukkit.Material.SOUL_SAND || below == org.bukkit.Material.SOUL_SOIL) {
            return 1.0 + 0.04 * level + 0.01;
        }
        return 1.0;
    }

    private static double depthStriderMultiplier(Player player) {
        var boots = player.getInventory().getBoots();
        if (boots == null || boots.getType() == org.bukkit.Material.AIR) return 1.0;
        int level = boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DEPTH_STRIDER);
        if (level <= 0) return 1.0;
        if (player.isInWater()) {
            return 1.0 + 0.33 * level;
        }
        return 1.0;
    }

    private static double swiftSneakMultiplier(Player player) {
        var leggings = player.getInventory().getLeggings();
        if (leggings == null || leggings.getType() == org.bukkit.Material.AIR) return 1.0;
        int level = leggings.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SWIFT_SNEAK);
        if (level <= 0 || !player.isSneaking()) return 1.0;
        return 1.0 + 0.15 * level;
    }

    private static double iceSpeedMultiplier(Player player) {
        var below = player.getLocation().clone().subtract(0, 1, 0).getBlock().getType();
        if (below == org.bukkit.Material.ICE || below == org.bukkit.Material.PACKED_ICE
                || below == org.bukkit.Material.BLUE_ICE || below == org.bukkit.Material.FROSTED_ICE) {
            if (player.isSprinting()) return 1.4;
            return 1.15;
        }
        return 1.0;
    }

    /** Extra allowed per-tick vertical climb (added to the check's base vertical tolerance) from Jump Boost. */
    public static double extraVerticalTolerance(Player player) {
        PotionEffect jump = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        if (jump == null) return 0.0;
        return 0.05 * (jump.getAmplifier() + 1);
    }

    /** Slow Falling / Levitation legitimately explain slow or upward "falls" that would otherwise look like flight. */
    public static boolean hasVerticalOverrideEffect(Player player) {
        return player.hasPotionEffect(PotionEffectType.SLOW_FALLING) || player.hasPotionEffect(PotionEffectType.LEVITATION);
    }
}
