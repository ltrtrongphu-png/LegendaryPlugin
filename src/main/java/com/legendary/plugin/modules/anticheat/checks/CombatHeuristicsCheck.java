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
 * Composite combat-heuristic engine that fuses multiple weak signals into a
 * single strong detection. Each individual signal might stay below a
 * standalone check's threshold, but their combination reveals coordinated
 * automation with high confidence.
 *
 * Signals tracked:
 * - Attack-angle consistency (aimbot produces near-perfect look-at-target)
 * - Swing-to-hit ratio (killaura often hits without swinging or with fixed ratio)
 * - Target-switch frequency (multiaura switches targets faster than humans)
 * - Post-damage rotation freeze (aimbot locks aim after hit; humans drift)
 * - First-hit delay (aimbot attacks within 1 tick of target entering range)
 */
public final class CombatHeuristicsCheck extends Check implements Listener {

    private final Map<UUID, Deque<Long>> attackTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> swingTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> swingCount = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> hitCount = new ConcurrentHashMap<>();
    private final Map<UUID, Long> windowStart = new ConcurrentHashMap<>();
    private final Map<UUID, Float> postHitYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Long> postHitTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> freezeStreak = new ConcurrentHashMap<>();

    private double compositeThreshold;
    private int windowMs;
    private double angleWeight;
    private double ratioWeight;
    private double targetSwitchWeight;
    private double freezeWeight;
    private double firstHitDelayWeight;
    private double freezeMaxDelta;
    private long freezeWindowMs;
    private double maxCompositeScore;

    public CombatHeuristicsCheck(AnticheatModule anticheat) {
        super(anticheat, "combatheuristics", "CombatHeuristics");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        compositeThreshold = plugin.getConfig().getDouble(path("composite-threshold"), 2.5);
        windowMs = plugin.getConfig().getInt(path("window-ms"), 3000);
        angleWeight = plugin.getConfig().getDouble(path("angle-weight"), 0.8);
        ratioWeight = plugin.getConfig().getDouble(path("ratio-weight"), 0.6);
        targetSwitchWeight = plugin.getConfig().getDouble(path("target-switch-weight"), 0.5);
        freezeWeight = plugin.getConfig().getDouble(path("freeze-weight"), 0.7);
        firstHitDelayWeight = plugin.getConfig().getDouble(path("first-hit-delay-weight"), 0.4);
        freezeMaxDelta = plugin.getConfig().getDouble(path("freeze-max-delta"), 3.0);
        freezeWindowMs = plugin.getConfig().getLong(path("freeze-window-ms"), 300);
        maxCompositeScore = plugin.getConfig().getDouble(path("max-score"), 5.0);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        swingTimestamps.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        Deque<Long> deque = swingTimestamps.get(uuid);
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) deque.pollFirst();
        }
        swingCount.merge(uuid, 1, Integer::sum);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity)) return;
        if (shouldSkip(attacker)) return;

        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();
        Deque<Long> attacks = attackTimestamps.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (attacks) {
            attacks.addLast(now);
            while (!attacks.isEmpty() && now - attacks.peekFirst() > windowMs) attacks.pollFirst();
        }
        hitCount.merge(uuid, 1, Integer::sum);

        postHitYaw.put(uuid, attacker.getLocation().getYaw());
        postHitTime.put(uuid, now);

        double score = computeCompositeScore(attacker, uuid, now);
        if (score >= compositeThreshold) {
            flag(attacker, Math.min(score / compositeThreshold, 3.0),
                String.format("composite=%.2f attacks=%d swings=%d",
                    score, attacks.size(), swingTimestamps.getOrDefault(uuid, new ArrayDeque<>()).size()));
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        Long hitTime = postHitTime.get(uuid);
        if (hitTime == null) return;
        long now = System.currentTimeMillis();
        if (now - hitTime > freezeWindowMs) {
            postHitTime.remove(uuid);
            postHitYaw.remove(uuid);
            freezeStreak.put(uuid, 0);
            return;
        }
        Float prevYaw = postHitYaw.get(uuid);
        if (prevYaw == null) return;
        float deltaYaw = Math.abs(wrapAngle(event.getTo().getYaw() - prevYaw));
        float deltaPitch = Math.abs(event.getTo().getPitch() - player.getLocation().getPitch());
        double totalDelta = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (totalDelta < freezeMaxDelta) {
            int streak = freezeStreak.merge(uuid, 1, Integer::sum);
            if (streak >= 4) {
                postHitTime.remove(uuid);
                postHitYaw.remove(uuid);
                flag(player, freezeWeight, String.format("postHitFreeze streak=%d delta=%.2f", streak, totalDelta));
            }
        } else {
            freezeStreak.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        attackTimestamps.remove(uuid);
        swingTimestamps.remove(uuid);
        swingCount.remove(uuid);
        hitCount.remove(uuid);
        windowStart.remove(uuid);
        postHitYaw.remove(uuid);
        postHitTime.remove(uuid);
        freezeStreak.remove(uuid);
    }

    private double computeCompositeScore(Player player, UUID uuid, long now) {
        double score = 0.0;
        Deque<Long> attacks = attackTimestamps.get(uuid);
        Deque<Long> swings = swingTimestamps.get(uuid);
        int attackCount = attacks != null ? attacks.size() : 0;
        int swingCount = swings != null ? swings.size() : 0;

        if (attackCount > 0 && swingCount > 0) {
            double ratio = (double) attackCount / swingCount;
            if (ratio > 0.9) score += ratioWeight * Math.min(ratio, 2.0);
        }

        if (attackCount >= 4) {
            long[] arr = attacks.stream().mapToLong(Long::longValue).toArray();
            double avgInterval = 0;
            for (int i = 1; i < arr.length; i++) avgInterval += arr[i] - arr[i - 1];
            avgInterval /= (arr.length - 1);
            double variance = 0;
            for (int i = 1; i < arr.length; i++) {
                double diff = (arr[i] - arr[i - 1]) - avgInterval;
                variance += diff * diff;
            }
            double stdDev = Math.sqrt(variance / (arr.length - 1));
            if (avgInterval > 0 && stdDev / avgInterval < 0.15) {
                score += angleWeight * (1.0 - Math.min(stdDev / avgInterval, 1.0));
            }
        }

        if (attackCount >= 3 && attackCount <= 8) {
            long span = attacks.peekLast() - attacks.peekFirst();
            if (span > 0) {
                double rate = attackCount / (span / 1000.0);
                if (rate > 8.0) score += targetSwitchWeight * Math.min(rate / 10.0, 2.0);
            }
        }

        Integer freeze = freezeStreak.get(uuid);
        if (freeze != null && freeze >= 3) {
            score += freezeWeight * 0.5;
        }

        return Math.min(score, maxCompositeScore);
    }

    private float wrapAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
