package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combined Killaura signature detector: reach, multi-target-in-a-blink
 * (multiaura) and abnormal click-rate/timing consistency (autoclicker).
 * Ported from BaB.KillAuraCheck.
 */
public final class KillAuraCheck extends Check implements Listener {

    private final Map<UUID, Deque<Long>> swingTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastHitTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHitTime = new ConcurrentHashMap<>();

    private double maxReach;
    private boolean multiauraEnabled;
    private long multiauraMinIntervalMs;
    private boolean autoclickerEnabled;
    private int autoclickerMaxCps;
    private long autoclickerSampleWindowMs;
    private boolean autoclickerTimingEnabled;
    private int autoclickerTimingMinSamples;
    private double autoclickerTimingMinCps;
    private boolean autoclickerStdDevEnabled;
    private double autoclickerStdDevThreshold;
    private int autoclickerStdDevMinSamples;

    public KillAuraCheck(AnticheatModule anticheat) {
        super(anticheat, "killaura", "KillAura");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxReach = plugin.getConfig().getDouble(path("max-reach"), 4.2);
        multiauraEnabled = plugin.getConfig().getBoolean(path("multiaura-enabled"), true);
        multiauraMinIntervalMs = plugin.getConfig().getLong(path("multiaura-min-interval-ms"), 60);
        autoclickerEnabled = plugin.getConfig().getBoolean(path("autoclicker-enabled"), true);
        autoclickerMaxCps = plugin.getConfig().getInt(path("autoclicker-max-cps"), 18);
        autoclickerSampleWindowMs = plugin.getConfig().getLong(path("autoclicker-sample-window-ms"), 2000);
        autoclickerTimingEnabled = plugin.getConfig().getBoolean(path("autoclicker-timing-enabled"), true);
        autoclickerTimingMinSamples = plugin.getConfig().getInt(path("autoclicker-timing-min-samples"), 10);
        autoclickerTimingMinCps = plugin.getConfig().getDouble(path("autoclicker-timing-min-cps"), 12.0);
        autoclickerStdDevEnabled = plugin.getConfig().getBoolean(path("autoclicker-stddev-enabled"), true);
        autoclickerStdDevThreshold = plugin.getConfig().getDouble(path("autoclicker-stddev-threshold"), 15.0);
        autoclickerStdDevMinSamples = plugin.getConfig().getInt(path("autoclicker-stddev-min-samples"), 15);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Deque<Long> deque = swingTimestamps.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && now - deque.peekFirst() > autoclickerSampleWindowMs) deque.pollFirst();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity target)) return;
        if (shouldSkip(attacker)) return;

        if (!attacker.getWorld().equals(target.getWorld())) return;
        double distance = attacker.getEyeLocation().distance(target.getEyeLocation());
        double compensation = anticheat.getTpsMonitor().getCompensationFactor();
        if (distance > maxReach * compensation) {
            flag(attacker, 1.0, String.format("reach=%.2f", distance));
        }

        if (multiauraEnabled) {
            UUID uuid = attacker.getUniqueId();
            long now = System.currentTimeMillis();
            UUID lastTarget = lastHitTarget.put(uuid, target.getUniqueId());
            Long lastTime = lastHitTime.put(uuid, now);
            if (lastTarget != null && !lastTarget.equals(target.getUniqueId())
                && lastTime != null && now - lastTime < multiauraMinIntervalMs) {
                flag(attacker, 1.0, "multiaura gap=" + (now - lastTime) + "ms");
            }
        }

        if (autoclickerEnabled) {
            Deque<Long> deque = swingTimestamps.get(attacker.getUniqueId());
            if (deque != null) {
                synchronized (deque) {
                    double cps = deque.size() / (autoclickerSampleWindowMs / 1000.0);
                    if (deque.size() >= autoclickerTimingMinSamples && cps > autoclickerMaxCps) {
                        flag(attacker, 0.75, String.format("cps=%.1f", cps));
                    }
                    if (autoclickerTimingEnabled && deque.size() >= autoclickerTimingMinSamples
                        && cps >= autoclickerTimingMinCps) {
                        double cv = computeTimingConsistency(deque);
                        if (cv < 0.08) {
                            flag(attacker, 0.75, String.format("timingCv=%.3f cps=%.1f", cv, cps));
                        }
                    }
                    if (autoclickerStdDevEnabled && deque.size() >= autoclickerStdDevMinSamples) {
                        double stdDev = computeClickStdDev(deque);
                        if (stdDev < autoclickerStdDevThreshold) {
                            flag(attacker, 1.0, String.format("clickStdDev=%.1f cps=%.1f", stdDev, cps));
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        swingTimestamps.remove(uuid);
        lastHitTarget.remove(uuid);
        lastHitTime.remove(uuid);
    }

    private double computeTimingConsistency(Deque<Long> timestamps) {
        Long[] arr = timestamps.toArray(new Long[0]);
        if (arr.length < 2) return 1.0;
        long[] deltas = new long[arr.length - 1];
        double sum = 0;
        for (int i = 1; i < arr.length; i++) {
            deltas[i - 1] = arr[i] - arr[i - 1];
            sum += deltas[i - 1];
        }
        double mean = sum / deltas.length;
        if (mean == 0) return 1.0;
        double sqSum = 0;
        for (long d : deltas) sqSum += (d - mean) * (d - mean);
        double stdDev = Math.sqrt(sqSum / deltas.length);
        return stdDev / mean;
    }

    /**
     * Computes the standard deviation of inter-click intervals in milliseconds.
     * A mechanical autoclicker produces intervals with near-zero standard
     * deviation (sigma ~ 0), while a human always has biological jitter
     * (sigma typically > 30ms). Threshold defaults to 15ms.
     */
    private double computeClickStdDev(Deque<Long> timestamps) {
        Long[] arr = timestamps.toArray(new Long[0]);
        if (arr.length < 2) return Double.MAX_VALUE;
        double sum = 0;
        int count = 0;
        for (int i = 1; i < arr.length; i++) {
            sum += arr[i] - arr[i - 1];
            count++;
        }
        double mean = sum / count;
        double sqSum = 0;
        for (int i = 1; i < arr.length; i++) {
            sqSum += Math.pow((arr[i] - arr[i - 1]) - mean, 2);
        }
        return Math.sqrt(sqSum / count);
    }
}
