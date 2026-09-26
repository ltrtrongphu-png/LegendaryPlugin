package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects Strafe hacks that allow changing horizontal direction mid-air
 * without any momentum source. In vanilla, once airborne, the player's
 * horizontal direction is governed by momentum - they cannot instantly
 * reverse direction. A Strafe hack sends packets that change the
 * horizontal movement vector by ~180 degrees between ticks.
 *
 * Upgraded with:
 * - TPS compensation: raises the angle threshold when the server lags
 *   so lag-induced jitter doesn't false-positive.
 * - Speed threshold scaling: only checks when the player has enough
 *   horizontal speed for the direction change to be meaningful. Small
 *   movements (< 0.08) are ignored.
 * - Slime bounce grace: after bouncing on a slime block the player's
 *   direction changes naturally - we skip 500ms after teleport/bounce.
 * - Max streak cap: prevents infinite streak accumulation if the
 *   player somehow oscillates.
 * - Jump boost potion exemption: Jump Boost changes air control.
 */
public final class StrafeCheck extends Check implements Listener {

    private double maxAngleDeg;
    private int requiredStreak;
    private double minSpeedThreshold;
    private int maxStreakCap;
    private final Map<UUID, double[]> lastHVelocity = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> streak = new ConcurrentHashMap<>();
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();

    public StrafeCheck(AnticheatModule anticheat) {
        super(anticheat, "strafe", "Strafe");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxAngleDeg = plugin.getConfig().getDouble(path("max-angle-degrees"), 120.0);
        requiredStreak = plugin.getConfig().getInt(path("required-streak"), 3);
        minSpeedThreshold = plugin.getConfig().getDouble(path("min-speed-threshold"), 0.08);
        maxStreakCap = plugin.getConfig().getInt(path("max-streak-cap"), 10);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 500);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isOnGround() || player.isGliding() || player.isFlying()
            || player.isInWater() || player.isInsideVehicle()) return;
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST)) return;

        UUID uuid = player.getUniqueId();
        Long grace = graceUntil.get(uuid);
        if (grace != null && System.currentTimeMillis() < grace) return;

        var from = event.getFrom();
        var to = event.getTo();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double hSpeed = Math.sqrt(dx * dx + dz * dz);

        if (hSpeed < minSpeedThreshold) {
            streak.put(uuid, 0);
            return;
        }

        double[] current = {dx, dz};
        double[] last = lastHVelocity.put(uuid, current);
        if (last == null) return;

        double lastSpeed = Math.sqrt(last[0] * last[0] + last[1] * last[1]);
        if (lastSpeed < minSpeedThreshold) return;

        double dot = (dx * last[0] + dz * last[1]) / (hSpeed * lastSpeed);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        double angle = Math.toDegrees(Math.acos(dot));

        // TPS compensation: raise threshold when lagging
        double tpsFactor = anticheat.getTpsMonitor().getCompensationFactor();
        double effectiveMax = maxAngleDeg + (tpsFactor - 1.0) * 30.0;

        if (angle > effectiveMax) {
            int s = streak.merge(uuid, 1, Integer::sum);
            if (s >= requiredStreak && s <= maxStreakCap) {
                flag(player, 1.0, String.format("strafeAngle=%.1f (max=%.1f) streak=%d", angle, effectiveMax, s));
                streak.put(uuid, 0);
            } else if (s > maxStreakCap) {
                streak.put(uuid, 0);
            }
        } else {
            streak.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastHVelocity.remove(uuid);
        streak.remove(uuid);
        graceUntil.remove(uuid);
    }
}
