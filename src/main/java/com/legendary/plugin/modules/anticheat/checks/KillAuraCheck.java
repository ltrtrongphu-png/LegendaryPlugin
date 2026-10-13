package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.util.BoundingBox;

/**
 * Combined Killaura signature detector: reach, multi-target-in-a-blink
 * (multiaura) and abnormal click-rate/timing consistency (autoclicker).
 * Ported from BaB.KillAuraCheck.
 */
public final class KillAuraCheck extends Check implements Listener {

    private final Map<UUID, Deque<Long>> swingTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastArmSwingAt = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastHitTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHitTime = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<TargetSnapshot>> targetHistory = new ConcurrentHashMap<>();

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
    private final Map<UUID, Integer> preAimStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastMoveRotationDelta = new ConcurrentHashMap<>();

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
        autoclickerStdDevEnabled = plugin.getConfig().getBoolean(path("autoclicker-stddev-enabled"), false);
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
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        UUID uuid = event.getPlayer().getUniqueId();
        lastArmSwingAt.put(uuid, System.currentTimeMillis());
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity target)) return;
        if (shouldSkip(attacker)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (target.hasMetadata("legendaryplugin-synthetic-damage")) return;

        if (!attacker.getWorld().equals(target.getWorld())) return;
        TargetSnapshot targetBox = getTargetBox(target, attacker.getPing());
        var attackerEye = attacker.getEyeLocation();
        double distance = distanceToBox(attackerEye.getX(), attackerEye.getY(), attackerEye.getZ(), targetBox);
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
            UUID uuid = attacker.getUniqueId();
            Long swingAt = lastArmSwingAt.remove(uuid);
            if (swingAt != null && System.currentTimeMillis() - swingAt <= 250L) {
                Deque<Long> deque = swingTimestamps.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
                boolean flagCps = false;
                boolean flagTiming = false;
                boolean flagStdDev = false;
                double cps;
                double timingCv = 1.0;
                double stdDev = Double.MAX_VALUE;
                synchronized (deque) {
                    long now = System.currentTimeMillis();
                    deque.addLast(swingAt);
                    while (!deque.isEmpty() && now - deque.peekFirst() > autoclickerSampleWindowMs) {
                        deque.pollFirst();
                    }
                    cps = deque.size() / (autoclickerSampleWindowMs / 1000.0);
                    if (deque.size() >= autoclickerTimingMinSamples) {
                        flagCps = cps > autoclickerMaxCps;
                        if (autoclickerTimingEnabled && cps >= autoclickerTimingMinCps) {
                            timingCv = computeTimingConsistency(deque);
                            flagTiming = timingCv < 0.08;
                        }
                    }
                    if (autoclickerStdDevEnabled && deque.size() >= autoclickerStdDevMinSamples) {
                        stdDev = computeClickStdDev(deque);
                        flagStdDev = stdDev < autoclickerStdDevThreshold;
                    }
                }
                if (flagCps) flag(attacker, 0.75, String.format("cps=%.1f", cps));
                if (flagTiming) flag(attacker, 0.75, String.format("timingCv=%.3f cps=%.1f", timingCv, cps));
                if (flagStdDev) flag(attacker, 1.0, String.format("clickStdDev=%.1f cps=%.1f", stdDev, cps));
            }
        }
        checkPreAim(attacker, target);
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
        long now = System.currentTimeMillis();
        lastMoveTime.put(uuid, now);
        if (event.getTo().getX() != event.getFrom().getX()
            || event.getTo().getY() != event.getFrom().getY()
            || event.getTo().getZ() != event.getFrom().getZ()) {
            addTargetSnapshot(player);
        }
        if (prevYaw == null) return;

        float deltaYaw = Math.abs(wrapAngle(yaw - prevYaw));
        float deltaPitch = Math.abs(pitch - prevPitch);
        double totalChange = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        lastMoveRotationDelta.put(uuid, totalChange);

        if (rotationSnapEnabled && totalChange > rotationSnapThreshold) {
            int streak = snapStreak.merge(uuid, 1, Integer::sum);
            if (streak >= rotationSnapMinConsecutive) {
                long currentTime = System.currentTimeMillis();
                Long lastFlag = lastRotFlagTime.get(uuid);
                if (lastFlag == null || currentTime - lastFlag >= rotFlagCooldownMs) {
                    flag(player, 0.85, String.format("rotSnap %.1fdeg streak=%d", totalChange, streak));
                    lastRotFlagTime.put(uuid, currentTime);
                }
                snapStreak.put(uuid, 0);
            }
        } else if (totalChange < 5.0) {
            snapStreak.put(uuid, 0);
        }

    }

    private void checkPreAim(Player attacker, LivingEntity target) {
        if (!preAimEnabled) return;
        UUID uuid = attacker.getUniqueId();
        Long movedAt = lastMoveTime.get(uuid);
        Double rotationDelta = lastMoveRotationDelta.get(uuid);
        if (movedAt == null || rotationDelta == null
            || System.currentTimeMillis() - movedAt > preAimWindowMs
            || rotationDelta <= preAimMaxAngleChange || rotationDelta >= rotationSnapThreshold) {
            preAimStreak.put(uuid, 0);
            return;
        }
        org.bukkit.util.Vector toTarget = target.getEyeLocation().toVector()
            .subtract(attacker.getEyeLocation().toVector()).normalize();
        double alignment = attacker.getEyeLocation().getDirection().dot(toTarget);
        if (alignment > 0.98) {
            int streak = preAimStreak.merge(uuid, 1, Integer::sum);
            if (streak >= 3) {
                flag(attacker, 0.5, String.format("preAim alignment=%.3f rotation=%.1f streak=%d",
                    alignment, rotationDelta, streak));
                preAimStreak.put(uuid, 0);
            }
        } else {
            preAimStreak.put(uuid, 0);
        }
    }

    private void addTargetSnapshot(Player player) {
        UUID uuid = player.getUniqueId();
        Deque<TargetSnapshot> history = targetHistory.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(snapshot(player, System.currentTimeMillis()));
            while (history.size() > 10) history.removeFirst();
        }
    }

    private TargetSnapshot getTargetBox(LivingEntity target, int attackerPing) {
        if (!(target instanceof Player targetPlayer)) {
            return snapshot(target, System.currentTimeMillis());
        }
        Deque<TargetSnapshot> history = targetHistory.get(targetPlayer.getUniqueId());
        if (history == null) return snapshot(target, System.currentTimeMillis());
        long targetTime = System.currentTimeMillis() - Math.min(500L, Math.max(0, attackerPing) / 2L);
        synchronized (history) {
            for (var iterator = history.descendingIterator(); iterator.hasNext();) {
                TargetSnapshot candidate = iterator.next();
                if (candidate.timestamp() <= targetTime
                    && System.currentTimeMillis() - candidate.timestamp() <= 500L) return candidate;
            }
        }
        return snapshot(target, System.currentTimeMillis());
    }

    private static TargetSnapshot snapshot(LivingEntity entity, long timestamp) {
        BoundingBox box = entity.getBoundingBox();
        if (box == null) {
            var eye = entity.getEyeLocation();
            return new TargetSnapshot(eye.getX() - 0.3, eye.getY() - 0.3, eye.getZ() - 0.3,
                eye.getX() + 0.3, eye.getY() + 0.3, eye.getZ() + 0.3, timestamp);
        }
        return new TargetSnapshot(box.getMinX(), box.getMinY(), box.getMinZ(),
            box.getMaxX(), box.getMaxY(), box.getMaxZ(), timestamp);
    }

    private static double distanceToBox(double x, double y, double z, TargetSnapshot box) {
        double nearestX = Math.max(box.minX(), Math.min(x, box.maxX()));
        double nearestY = Math.max(box.minY(), Math.min(y, box.maxY()));
        double nearestZ = Math.max(box.minZ(), Math.min(z, box.maxZ()));
        return Math.sqrt(Math.pow(x - nearestX, 2) + Math.pow(y - nearestY, 2) + Math.pow(z - nearestZ, 2));
    }

    private record TargetSnapshot(double minX, double minY, double minZ, double maxX, double maxY,
                                  double maxZ, long timestamp) {}

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        targetHistory.remove(uuid);
        lastHitTarget.remove(uuid);
        lastHitTime.remove(uuid);
        lastMoveTime.remove(uuid);
        lastMoveRotationDelta.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        swingTimestamps.remove(uuid);
        lastArmSwingAt.remove(uuid);
        lastHitTarget.remove(uuid);
        lastHitTime.remove(uuid);
        lastYaw.remove(uuid);
        lastPitch.remove(uuid);
        snapStreak.remove(uuid);
        lastRotFlagTime.remove(uuid);
        lastMoveTime.remove(uuid);
        preAimStreak.remove(uuid);
        lastMoveRotationDelta.remove(uuid);
        targetHistory.remove(uuid);
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
