package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

/** Flags a victim's velocity failing to change after taking a hit (anti-knockback / no-knockback hacks). */
public final class KnockbackCheck extends Check implements Listener {

    private int delayTicks;
    private double minVelocity;

    public KnockbackCheck(AnticheatModule anticheat) {
        super(anticheat, "knockback", "AntiKnockback");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        delayTicks = ConfigUtil.getBoundedInt(plugin, path("delay-ticks"), 10, 2, 40);
        minVelocity = ConfigUtil.getBoundedDouble(plugin, path("min-velocity"), 0.15, 0.02, 1.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (shouldSkip(victim)) return;
        if (!(event.getDamager() instanceof LivingEntity attacker)) return;

        Vector beforeVelocity = victim.getVelocity().clone();
        if (!victim.getWorld().equals(attacker.getWorld())) return;
        Vector knockbackDir = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
        knockbackDir.setY(0);
        if (knockbackDir.lengthSquared() < 1e-6) knockbackDir = new Vector(0, 0, 1);
        knockbackDir.normalize();
        final Vector kbDir = knockbackDir;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!victim.isOnline()) return;
            Vector after = victim.getVelocity();
            double delta = after.clone().subtract(beforeVelocity).length();
            if (delta < minVelocity && !isBlockedDirection(victim, kbDir)) {
                flag(victim, 1.0, String.format("delta=%.3f", delta));
            }
        }, delayTicks);
    }

    /** Standing against a wall legitimately absorbs horizontal knockback - don't punish that. */
    private boolean isBlockedDirection(Player victim, Vector knockbackDir) {
        org.bukkit.block.BlockFace face = vectorToBlockFace(knockbackDir);
        return !victim.getLocation().getBlock().getRelative(face).isEmpty();
    }

    private org.bukkit.block.BlockFace vectorToBlockFace(Vector v) {
        double absX = Math.abs(v.getX());
        double absZ = Math.abs(v.getZ());
        if (absX > absZ) {
            return v.getX() > 0 ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST;
        } else {
            return v.getZ() > 0 ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
        }
    }
}
