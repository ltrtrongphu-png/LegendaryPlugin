package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerMoveEvent;
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

    // Rotation snap detection (catches subtle aimbot that adds jitter to bypass timing checks)
    private boolean rotationSnapEnabled;
    private double rotationSnapThreshold; // max degrees/tick considered "snap"
    private int rotationSnapMinConsecutive;
    private final Map<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastPitch = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> snapStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRotFlagTime = new ConcurrentHashMap<>();
    private long rotFlagCooldownMs;

    // Pre-aim detection (player snaps to target right before hitting)
    private boolean preAimEnabled;
    private double preAimMaxAngleChange;
    private long preAimWindowMs;
    private final Map<UUID, Long> lastMoveTime = new ConcurrentHashMap<>();
    private final Map<UUID, Float> yawBeforeHit = new ConcurrentHashMap<>();

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
        rotationSnapEnabled = plugin.getConfig().getBoolean(path("rotation-snap-enabled"), true);
        rotationSnapThreshold = plugin.getConfig().getDouble(path("rotation-snap-threshold"), 55.0);
        rotationSnapMinConsecutive = plugin.getConfig().getInt(path("rotation-snap-min-consecutive"), 3);
        rotFlagCooldownMs = plugin.getConfig().getLong(path("rot-flag-cooldown-ms"), 2000);
        preAimEnabled = plugin.getConfig().getBoolean(path("pre-aim-enabled"), true);
        preAimMaxAngleChange = plugin.getConfig().getDouble(path("pre-aim-max-angle-change"), 30.0);
        preAimWindowMs = plugin.getConfig().getLong(path("pre-aim-window-ms"), 150);
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
    public void onMove(PlayerMoveEvent event) {
        if (!rotationSnapEnabled && !preAimEnabled) return;
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        float yaw = event.getTo().getYaw();
        float pitch = event.getTo().getPitch();
        Float prevYaw = lastYaw.put(uuid, yaw);
        Float prevPitch = lastPitch.put(uuid, pitch);
        lastMoveTime.put(uuid, System.currentTimeMillis());
        if (prevYaw == null) return;

        float deltaYaw = Math.abs(wrapAngle(yaw - prevYaw));
        float deltaPitch = Math.abs(pitch - prevPitch);
        double totalChange = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);

        if (rotationSnapEnabled && totalChange > rotationSnapThreshold) {
            int streak = snapStreak.merge(uuid, 1, Integer::sum);
            if (streak >= rotationSnapMinConsecutive) {
                long now = System.currentTimeMillis();
                Long lastFlag = lastRotFlagTime.get(uuid);
                if (lastFlag == null || now - lastFlag >= rotFlagCooldownMs) {
                    flag(player, 0.85, String.format("rotSnap %.1fdeg streak=%d", totalChange, streak));
                    lastRotFlagTime.put(uuid, now);
                }
                snapStreak.put(uuid, 0);
            }
        } else if (totalChange < 5.0) {
            snapStreak.put(uuid, 0);
        }

        if (preAimEnabled) {
            yawBeforeHit.put(uuid, yaw);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        swingTimestamps.remove(uuid);
        lastHitTarget.remove(uuid);
        lastHitTime.remove(uuid);
        lastYaw.remove(uuid);
        lastPitch.remove(uuid);
        snapStreak.remove(uuid);
        lastRotFlagTime.remove(uuid);
        lastMoveTime.remove(uuid);
        yawBeforeHit.remove(uuid);
    }

    private float wrapAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
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
