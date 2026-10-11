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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flags a victim's velocity failing to change after taking a hit
 * (anti-knockback / no-knockback hacks).
 *
 * Two complementary detection paths:
 * 1. Delayed measurement (original): records the victim's velocity
 *    before the hit and re-checks after delayTicks to see whether the
 *    velocity delta is below the minimum. Skips when a wall blocks the
 *    knockback direction (legitimate absorption).
 * 2. PlayerVelocityEvent hook (folded from the former VelocityCheck):
 *    tracks pending knockback from EntityDamageByEntityEvent, then in
 *    PlayerVelocityEvent checks whether the applied velocity magnitude
 *    is below min-knockback-magnitude. A required-streak counter avoids
 *    flagging single lag spikes.
 */
public final class KnockbackCheck extends Check implements Listener {

    private int delayTicks;
    private double minVelocity;

    // PlayerVelocityEvent path (folded from VelocityCheck)
    private double minKbMagnitude;
    private int requiredStreak;
    private final Map<UUID, Integer> pendingKnockback = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> velocityStreak = new ConcurrentHashMap<>();

    public KnockbackCheck(AnticheatModule anticheat) {
        super(anticheat, "knockback", "AntiKnockback");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        delayTicks = ConfigUtil.getBoundedInt(plugin, path("delay-ticks"), 10, 2, 40);
        minVelocity = ConfigUtil.getBoundedDouble(plugin, path("min-velocity"), 0.15, 0.02, 1.0);
        minKbMagnitude = plugin.getConfig().getDouble(path("min-knockback-magnitude"), 0.12);
        requiredStreak = plugin.getConfig().getInt(path("required-streak"), 3);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (shouldSkip(victim)) return;
        if (!(event.getDamager() instanceof LivingEntity attacker)) return;

        // Track pending knockback for the PlayerVelocityEvent path.
        pendingKnockback.merge(victim.getUniqueId(), 1, Integer::sum);

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

    /**
     * PlayerVelocityEvent path (folded from VelocityCheck): when the
     * server applies knockback velocity, check that the magnitude is
     * not suspiciously low. A streak counter avoids flagging single
     * anomalies.
     */
    @EventHandler
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        int pending = pendingKnockback.getOrDefault(uuid, 0);
        if (pending <= 0) return;
        pendingKnockback.merge(uuid, -1, (a, b) -> Math.max(0, a + b));

        double mag = event.getVelocity().length();
        if (mag < minKbMagnitude) {
            int s = velocityStreak.merge(uuid, 1, Integer::sum);
            if (s >= requiredStreak) {
                flag(player, 1.5, String.format("kbMag=%.3f (min=%.3f) streak=%d", mag, minKbMagnitude, s));
                velocityStreak.put(uuid, 0);
            }
        } else {
            velocityStreak.put(uuid, 0);
        }
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingKnockback.remove(uuid);
        velocityStreak.remove(uuid);
    }
}
