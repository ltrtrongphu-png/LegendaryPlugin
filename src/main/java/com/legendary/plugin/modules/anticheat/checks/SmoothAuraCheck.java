package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmoothAura: detects smooth aimbots that gradually converge rotation
 * toward a target over multiple ticks (unlike KillAura's snap detection).
 * The signature is that rotation deltas consistently point toward a
 * nearby entity over several consecutive ticks, with low variance in
 * the angle-of-approach. A human's aim wanders; a smooth aimbot's
 * corrections are always in the direction of the target.
 */
public final class SmoothAuraCheck extends Check implements Listener {

    private final Map<UUID, Deque<Double>> convergenceSamples = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastConvergenceFlag = new ConcurrentHashMap<>();

    private int sampleSize;
    private double minConvergenceRatio;
    private double minRotationPerTick;
    private long flagCooldownMs;

    public SmoothAuraCheck(AnticheatModule anticheat) {
        super(anticheat, "smoothaura", "SmoothAura");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        sampleSize = plugin.getConfig().getInt(path("sample-size"), 15);
        minConvergenceRatio = plugin.getConfig().getDouble(path("min-convergence-ratio"), 0.8);
        minRotationPerTick = plugin.getConfig().getDouble(path("min-rotation-per-tick"), 2.0);
        flagCooldownMs = plugin.getConfig().getLong(path("flag-cooldown-ms"), 3000);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();

        // Find nearest living entity within 6 blocks
        var nearby = player.getNearbyEntities(6, 4, 6);
        LivingEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (var ent : nearby) {
            if (!(ent instanceof LivingEntity le) || le == player) continue;
            double d = player.getLocation().distance(le.getLocation());
            if (d < nearestDist) { nearestDist = d; nearest = le; }
        }
        if (nearest == null) {
            convergenceSamples.remove(uuid);
            return;
        }

        // Compute whether the rotation change moves toward the target
        Vector lookDir = event.getTo().getDirection();
        Vector toTarget = nearest.getEyeLocation().toVector()
            .subtract(player.getEyeLocation().toVector()).normalize();
        double alignment = lookDir.dot(toTarget);

        // Check if this tick's rotation moved closer to target alignment
        Vector prevLook = event.getFrom().getDirection();
        double prevAlignment = prevLook.dot(toTarget);

        double rotationMagnitude = Math.sqrt(
            Math.pow(event.getTo().getYaw() - event.getFrom().getYaw(), 2)
            + Math.pow(event.getTo().getPitch() - event.getFrom().getPitch(), 2));

        if (rotationMagnitude < minRotationPerTick) return;

        // 1.0 = rotation moved perfectly toward target, 0.0 = away
        double convergence = alignment > prevAlignment ? 1.0 : 0.0;

        Deque<Double> samples = convergenceSamples.computeIfAbsent(uuid, k -> new ArrayDeque<>(sampleSize));
        synchronized (samples) {
            samples.addLast(convergence);
            while (samples.size() > sampleSize) samples.pollFirst();

            if (samples.size() >= sampleSize) {
                long converging = samples.stream().filter(v -> v == 1.0).count();
                double ratio = (double) converging / samples.size();
                if (ratio >= minConvergenceRatio) {
                    long now = System.currentTimeMillis();
                    Long lastFlag = lastConvergenceFlag.get(uuid);
                    if (lastFlag == null || now - lastFlag >= flagCooldownMs) {
                        flag(player, 1.0, String.format("convergenceRatio=%.2f over %d ticks", ratio, samples.size()));
                        lastConvergenceFlag.put(uuid, now);
                    }
                    samples.clear();
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        convergenceSamples.remove(uuid);
        lastConvergenceFlag.remove(uuid);
    }
}
