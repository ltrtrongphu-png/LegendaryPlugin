package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReachProfiling: statistically profiles a player's attack reach over
 * time. Instead of flagging a single reach violation, this check
 * accumulates reach distances and flags when the distribution is
 * suspiciously high — either the average reach is above the vanilla
 * cap, or the player consistently hits at near-maximum reach (which
 * indicates a reach extension hack). Vanilla maximum reach is ~3.0
 * blocks (survival) with a small tolerance for latency.
 */
public final class ReachProfilingCheck extends Check implements Listener {

    private final Map<UUID, Deque<Double>> reachSamples = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlagTs = new ConcurrentHashMap<>();

    private int minSamples;
    private double maxAvgReach;
    private double maxSingleReach;
    private double maxReachConsistency;
    private long flagCooldownMs;
    private double compensationFactor;

    public ReachProfilingCheck(AnticheatModule anticheat) {
        super(anticheat, "reachprofiling", "ReachProfiling");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minSamples = plugin.getConfig().getInt(path("min-samples"), 10);
        maxAvgReach = plugin.getConfig().getDouble(path("max-avg-reach"), 3.2);
        maxSingleReach = plugin.getConfig().getDouble(path("max-single-reach"), 4.2);
        maxReachConsistency = plugin.getConfig().getDouble(path("max-reach-consistency"), 0.15);
        flagCooldownMs = plugin.getConfig().getLong(path("flag-cooldown-ms"), 5000);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (shouldSkip(attacker)) return;
        if (!attacker.getWorld().equals(target.getWorld())) return;

        double distance = attacker.getEyeLocation().distance(target.getEyeLocation());
        double compensation = anticheat.getTpsMonitor().getCompensationFactor();

        // Flag single extreme reach
        if (distance > maxSingleReach * compensation) {
            flag(attacker, 1.5, String.format("singleReach=%.2f max=%.2f", distance, maxSingleReach * compensation));
        }

        UUID uuid = attacker.getUniqueId();
        Deque<Double> samples = reachSamples.computeIfAbsent(uuid, k -> new ArrayDeque<>(minSamples));
        synchronized (samples) {
            samples.addLast(distance);
            while (samples.size() > minSamples * 2) samples.pollFirst();

            if (samples.size() >= minSamples) {
                double avg = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double cv = computeCV(samples);

                long now = System.currentTimeMillis();
                Long lastFlag = lastFlagTs.get(uuid);

                // Flag if average reach is above cap
                if (avg > maxAvgReach * compensation) {
                    if (lastFlag == null || now - lastFlag >= flagCooldownMs) {
                        flag(attacker, 1.0, String.format("avgReach=%.2f over %d hits (max=%.2f)",
                            avg, samples.size(), maxAvgReach * compensation));
                        lastFlagTs.put(uuid, now);
                        samples.clear();
                    }
                }
                // Flag if reach is suspiciously consistent at high values (reach hack with smoothing)
                else if (avg > 2.8 && cv < maxReachConsistency) {
                    if (lastFlag == null || now - lastFlag >= flagCooldownMs) {
                        flag(attacker, 0.85, String.format("consistentReach avg=%.2f cv=%.3f", avg, cv));
                        lastFlagTs.put(uuid, now);
                        samples.clear();
                    }
                }
            }
        }
    }

    private double computeCV(Deque<Double> samples) {
        double[] arr = samples.stream().mapToDouble(Double::doubleValue).toArray();
        if (arr.length < 2) return 1.0;
        double sum = 0;
        for (double v : arr) sum += v;
        double mean = sum / arr.length;
        double sqSum = 0;
        for (double v : arr) sqSum += (v - mean) * (v - mean);
        double stdDev = Math.sqrt(sqSum / arr.length);
        return mean > 0 ? stdDev / mean : 1.0;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        reachSamples.remove(uuid);
        lastFlagTs.remove(uuid);
    }
}
