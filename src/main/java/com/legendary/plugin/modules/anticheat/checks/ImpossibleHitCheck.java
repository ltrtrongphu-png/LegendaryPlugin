package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

/** Flags hits where the target is behind the attacker's field of view entirely (angle check independent of reach). */
public final class ImpossibleHitCheck extends Check implements Listener {

    private double maxNegativeDot;
    private double maxFovDegrees;

    public ImpossibleHitCheck(AnticheatModule anticheat) {
        super(anticheat, "impossiblehit", "ImpossibleHit");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxNegativeDot = plugin.getConfig().getDouble(path("max-negative-dot"), -0.05);
        maxFovDegrees = plugin.getConfig().getDouble(path("max-fov-degrees"), 100.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity target)) return;
        if (shouldSkip(attacker)) return;

        Vector toTarget = target.getEyeLocation().toVector().subtract(attacker.getEyeLocation().toVector());
        if (toTarget.lengthSquared() < 1e-6) return;
        toTarget.normalize();
        Vector look = attacker.getEyeLocation().getDirection().normalize();
        double dot = toTarget.dot(look);
        if (dot < maxNegativeDot) {
            flag(attacker, 1.0, String.format("dot=%.3f (target behind attacker)", dot));
            return;
        }
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        if (angle > maxFovDegrees) {
            flag(attacker, 1.0, String.format("angle=%.1f (outside FOV)", angle));
        }
    }
}
