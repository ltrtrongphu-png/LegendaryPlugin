package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects velocity manipulation (AntiBKnockback / Velocity hacks).
 * When a player is hit, we record the velocity the server assigned. On
 * the next PlayerVelocityEvent we verify the outgoing velocity magnitude
 * is not drastically lower than expected, which would indicate the client
 * is zeroing or reducing the knockback vector before applying it.
 *
 * We also catch plugins / hacks that call Player#setVelocity with a
 * near-zero vector immediately after a legitimate knockback event.
 */
public final class VelocityCheck extends Check implements Listener {

    private final Set<UUID> pendingKnockback = ConcurrentHashMap.newKeySet();
    private double minKbMagnitude;
    private int requiredStreak;
    private final Map<UUID, Integer> streak = new ConcurrentHashMap<>();

    public VelocityCheck(AnticheatModule anticheat) {
        super(anticheat, "velocity", "VelocityManipulation");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minKbMagnitude = plugin.getConfig().getDouble(path("min-knockback-magnitude"), 0.12);
        requiredStreak = plugin.getConfig().getInt(path("required-streak"), 3);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (shouldSkip(victim)) return;
        // Record that a knockback is incoming for this victim
        pendingKnockback.add(victim.getUniqueId());
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        if (!pendingKnockback.remove(uuid)) return;

        double mag = event.getVelocity().length();
        if (mag < minKbMagnitude) {
            int s = streak.merge(uuid, 1, Integer::sum);
            if (s >= requiredStreak) {
                flag(player, 1.5, String.format("kbMag=%.3f (min=%.3f) streak=%d", mag, minKbMagnitude, s));
                streak.put(uuid, 0);
            }
        } else {
            streak.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingKnockback.remove(uuid);
        streak.remove(uuid);
    }
}
