package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * NEW CHECK (2026-09-19 DeepSeek-dump merge review). Vanilla only marks a
 * hit as "critical" (the star-particle bonus-damage hit) when the
 * attacker is falling (not on ground, not climbing, not in water/gliding)
 * at the moment of impact. A hacked client that forces every hit to
 * register as critical while standing still on the ground is an
 * always-crit signature this check catches directly from the event's own
 * {@code isCritical()} flag cross-checked against the attacker's actual
 * ground state.
 */
public final class CriticalsCheck extends Check implements Listener {

    public CriticalsCheck(AnticheatModule anticheat) {
        super(anticheat, "criticals", "Criticals");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity)) return;
        if (shouldSkip(attacker)) return;
        if (event.isCritical() && attacker.isOnGround() && !attacker.isInWater() && !attacker.isClimbing()) {
            flag(attacker, 1.0, "critical hit while grounded");
        }
    }
}
