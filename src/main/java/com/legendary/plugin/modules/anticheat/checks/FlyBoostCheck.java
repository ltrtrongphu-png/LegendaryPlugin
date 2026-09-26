package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import com.legendary.plugin.modules.anticheat.physics.VanillaPhysics;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects abnormal horizontal speed while airborne (FlySpeed/Glide hacks).
 * Complements MovementCheck by specifically targeting the airborne phase:
 * a player who is not on ground, not gliding, not in a vehicle, and not
 * affected by Jump Boost should not be able to sustain high horizontal speed.
 *
 * Uses a streak counter to avoid flagging single lag spikes.
 */
public final class FlyBoostCheck extends Check implements Listener {

    private double maxAirSpeed;
    private int requiredStreak;
    private final Map<UUID, Integer> airSpeedStreak = new ConcurrentHashMap<>();

    public FlyBoostCheck(AnticheatModule anticheat) {
        super(anticheat, "flyboost", "FlyBoost");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxAirSpeed = plugin.getConfig().getDouble(path("max-air-speed"), 0.55);
        requiredStreak = plugin.getConfig().getInt(path("required-streak"), 4);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isOnGround() || player.isGliding() || player.isFlying()
                || player.isInWater() || player.isInsideVehicle()) return;
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST)) return;

        var from = event.getFrom();
        var to = event.getTo();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double hSpeed = Math.sqrt(dx * dx + dz * dz);

        // Apply TPS compensation so lag doesn't produce false positives
        double factor = anticheat.getTpsMonitor().getCompensationFactor();
        double effectiveMax = maxAirSpeed * factor * VanillaPhysics.horizontalSpeedMultiplier(player);

        UUID uuid = player.getUniqueId();
        if (hSpeed > effectiveMax) {
            int s = airSpeedStreak.merge(uuid, 1, Integer::sum);
            if (s >= requiredStreak) {
                flag(player, 1.2, String.format("airSpeed=%.3f (max=%.3f) streak=%d", hSpeed, effectiveMax, s));
                airSpeedStreak.put(uuid, 0);
            }
        } else {
            airSpeedStreak.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        airSpeedStreak.remove(event.getPlayer().getUniqueId());
    }
}
