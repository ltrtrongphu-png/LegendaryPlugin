package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NoKnockback: detects players who reduce or cancel knockback via hack clients.
 * Unlike KnockbackCheck which measures velocity delta after a delay, this check
 * compares the player's position displacement immediately after being hit
 * (1 tick later) with the expected knockback vector. If the player barely
 * moved despite receiving a valid melee hit, they are likely using
 * anti-knockback. This complements KnockbackCheck by catching the "reduce"
 * variant where velocity is partially cancelled (not fully zeroed).
 */
public final class NoKnockbackCheck extends Check implements Listener {

    private final Map<UUID, Vector> preHitVelocity = new ConcurrentHashMap<>();
    private final Map<UUID, Vector> preHitPos = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hitTimestamp = new ConcurrentHashMap<>();
    private double minDisplacement;
    private double maxReduceRatio;
    private int checkDelayTicks;

    public NoKnockbackCheck(AnticheatModule anticheat) {
        super(anticheat, "noknockback", "NoKnockback");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minDisplacement = plugin.getConfig().getDouble(path("min-displacement"), 0.05);
        maxReduceRatio = plugin.getConfig().getDouble(path("max-reduce-ratio"), 0.15);
        checkDelayTicks = plugin.getConfig().getInt(path("check-delay-ticks"), 2);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (shouldSkip(victim)) return;
        if (!(event.getDamager() instanceof LivingEntity attacker)) return;
        if (!victim.getWorld().equals(attacker.getWorld())) return;

        preHitVelocity.put(victim.getUniqueId(), victim.getVelocity().clone());
        preHitPos.put(victim.getUniqueId(), victim.getLocation().toVector().clone());
        hitTimestamp.put(victim.getUniqueId(), System.currentTimeMillis());

        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!victim.isOnline()) return;
            UUID uuid = victim.getUniqueId();
            Vector before = preHitVelocity.remove(uuid);
            Vector beforePos = preHitPos.remove(uuid);
            hitTimestamp.remove(uuid);
            if (before == null || beforePos == null) return;

            Vector after = victim.getVelocity();
            Vector delta = after.clone().subtract(before);
            double horizontalDelta = Math.sqrt(delta.getX() * delta.getX() + delta.getZ() * delta.getZ());

            Vector posDelta = victim.getLocation().toVector().subtract(beforePos);
            double posDisplacement = Math.sqrt(posDelta.getX() * posDelta.getX() + posDelta.getZ() * posDelta.getZ());

            if (posDisplacement < minDisplacement && horizontalDelta < 0.1) {
                flag(victim, 1.0, String.format("disp=%.3f hDelta=%.3f", posDisplacement, horizontalDelta));
            }
        }, checkDelayTicks);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        preHitVelocity.remove(uuid);
        preHitPos.remove(uuid);
        hitTimestamp.remove(uuid);
    }
}
