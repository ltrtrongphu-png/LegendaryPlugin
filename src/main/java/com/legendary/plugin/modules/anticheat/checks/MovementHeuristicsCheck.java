package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import java.util.Deque;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Composite movement-heuristic engine that fuses multiple weak movement
 * signals into a single strong detection. Catches fly hacks, speed hacks,
 * and packet-tampering that individually stay below standalone check
 * thresholds but are obvious when combined.
 *
 * Signals tracked:
 * - Vertical acceleration profile (legitimate gravity produces predictable V profile)
 * - Horizontal burst pattern (speed hacks produce sustained bursts, not spikes)
 * - Sprint-state inconsistency (sprinting without sprint flag, or sprint-jump anomaly)
 * - Velocity-response mismatch (server sends knockback, player doesn't move — NoKB variant)
 * - Air-time accumulation (sustained air time without elytra/levitation = flight)
 */
public final class MovementHeuristicsCheck extends Check implements Listener {

    private final Map<UUID, Deque<Double>> verticalVelocities = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Double>> horizontalSpeeds = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> sprintState = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> airTimeTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Location> velocityExpected = new ConcurrentHashMap<>();
    private final Map<UUID, Long> velocityTime = new ConcurrentHashMap<>();
    private final Map<UUID, Double> velocityMagnitude = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastLoc = new ConcurrentHashMap<>();
    private final Map<UUID, Double> compositeScore = new ConcurrentHashMap<>();

    private double scoreThreshold;
    private int profileSamples;
    private double verticalWeight;
    private double horizontalWeight;
    private double sprintWeight;
    private double velocityWeight;
    private double airTimeWeight;
    private int airTimeThreshold;
    private double maxScore;
    private long velocityResponseMs;

    public MovementHeuristicsCheck(AnticheatModule anticheat) {
        super(anticheat, "movementheuristics", "MovementHeuristics");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        scoreThreshold = plugin.getConfig().getDouble(path("score-threshold"), 2.0);
        profileSamples = plugin.getConfig().getInt(path("profile-samples"), 10);
        verticalWeight = plugin.getConfig().getDouble(path("vertical-weight"), 0.7);
        horizontalWeight = plugin.getConfig().getDouble(path("horizontal-weight"), 0.6);
        sprintWeight = plugin.getConfig().getDouble(path("sprint-weight"), 0.4);
        velocityWeight = plugin.getConfig().getDouble(path("velocity-weight"), 0.8);
        airTimeWeight = plugin.getConfig().getDouble(path("airtime-weight"), 0.5);
        airTimeThreshold = plugin.getConfig().getInt(path("airtime-threshold-ticks"), 40);
        maxScore = plugin.getConfig().getDouble(path("max-score"), 5.0);
        velocityResponseMs = plugin.getConfig().getLong(path("velocity-response-ms"), 300);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (event.getTo() == null) return;

        UUID uuid = player.getUniqueId();
        Location from = event.getFrom();
        Location to = event.getTo();
        Location prev = lastLoc.put(uuid, to);
        if (prev == null) return;
        if (!from.getWorld().equals(to.getWorld())) return;

        double dy = to.getY() - from.getY();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double hSpeed = Math.sqrt(dx * dx + dz * dz);

        Deque<Double> vVel = verticalVelocities.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (vVel) {
            vVel.addLast(dy);
            while (vVel.size() > profileSamples) vVel.pollFirst();
        }
        Deque<Double> hVel = horizontalSpeeds.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (hVel) {
            hVel.addLast(hSpeed);
            while (hVel.size() > profileSamples) hVel.pollFirst();
        }

        double score = 0.0;

        if (vVel.size() >= 4) {
            Double[] arr = vVel.toArray(new Double[0]);
            int n = arr.length;
            boolean sustainedPositive = true;
            int positiveCount = 0;
            for (int i = Math.max(0, n - 5); i < n; i++) {
                if (arr[i] > 0.05) positiveCount++;
                if (arr[i] <= 0) sustainedPositive = false;
            }
            if (sustainedPositive && positiveCount >= 4 && !player.isGliding() && !player.isFlying()) {
                score += verticalWeight * 2.0;
            }
            boolean noGravity = true;
            for (int i = Math.max(0, n - 4); i < n; i++) {
                if (arr[i] < -0.1) { noGravity = false; break; }
            }
            if (noGravity && !player.isOnGround() && !player.isGliding() && !player.isFlying()) {
                score += verticalWeight;
            }
        }

        if (hVel.size() >= 5) {
            Double[] arr = hVel.toArray(new Double[0]);
            int n = arr.length;
            double sum = 0;
            for (int i = Math.max(0, n - profileSamples); i < n; i++) sum += arr[i];
            double avg = sum / Math.min(n, profileSamples);
            double comp = anticheat.getTpsMonitor().getCompensationFactor();
            double sprintCap = 0.28 * 1.3 * comp;
            if (avg > sprintCap * 1.02 && player.isOnGround()) {
                score += horizontalWeight * Math.min(avg / sprintCap, 2.0);
            }
            double variance = 0;
            for (int i = Math.max(0, n - profileSamples); i < n; i++) {
                variance += (arr[i] - avg) * (arr[i] - avg);
            }
            double stdDev = Math.sqrt(variance / Math.min(n, profileSamples));
            if (avg > 0.2 && stdDev / avg < 0.05) {
                score += horizontalWeight * 0.5;
            }
        }

        Long velTime = velocityTime.get(uuid);
        if (velTime != null) {
            long now = System.currentTimeMillis();
            if (now - velTime < velocityResponseMs) {
                Location expected = velocityExpected.get(uuid);
                Double mag = velocityMagnitude.get(uuid);
                if (expected != null && mag != null && mag > 0.3) {
                    double actualH = Math.sqrt(dx * dx + dz * dz);
                    if (actualH < mag * 0.3) {
                        score += velocityWeight;
                    }
                }
            } else {
                velocityTime.remove(uuid);
                velocityExpected.remove(uuid);
                velocityMagnitude.remove(uuid);
            }
        }

        if (!player.isOnGround() && !player.isGliding() && !player.isFlying()) {
            int air = airTimeTicks.merge(uuid, 1, Integer::sum);
            if (air >= airTimeThreshold) {
                double avgDy = 0;
                Deque<Double> v = verticalVelocities.get(uuid);
                if (v != null && !v.isEmpty()) {
                    synchronized (v) {
                        for (Double d : v) avgDy += d;
                        avgDy /= v.size();
                    }
                }
                if (Math.abs(avgDy) < 0.05) {
                    score += airTimeWeight * 2.0;
                }
            }
        } else {
            airTimeTicks.put(uuid, 0);
        }

        Boolean sprinting = sprintState.get(uuid);
        if (sprinting != null && sprinting && !player.isSprinting() && hSpeed > 0.25) {
            score += sprintWeight;
        }

        score = Math.min(score, maxScore);
        compositeScore.put(uuid, score);
        if (score >= scoreThreshold) {
            flag(player, Math.min(score / scoreThreshold, 2.5),
                String.format("composite=%.2f hSpeed=%.3f dy=%.3f air=%d",
                    score, hSpeed, dy, airTimeTicks.getOrDefault(uuid, 0)));
        }
    }

    @EventHandler
    public void onSprint(PlayerToggleSprintEvent event) {
        sprintState.put(event.getPlayer().getUniqueId(), event.isSprinting());
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        velocityExpected.put(uuid, event.getPlayer().getLocation().clone().add(event.getVelocity()));
        velocityMagnitude.put(uuid, Math.sqrt(event.getVelocity().getX() * event.getVelocity().getX()
            + event.getVelocity().getZ() * event.getVelocity().getZ()));
        velocityTime.put(uuid, System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        verticalVelocities.remove(uuid);
        horizontalSpeeds.remove(uuid);
        sprintState.remove(uuid);
        airTimeTicks.remove(uuid);
        velocityExpected.remove(uuid);
        velocityTime.remove(uuid);
        velocityMagnitude.remove(uuid);
        lastLoc.remove(uuid);
        compositeScore.remove(uuid);
    }
}
