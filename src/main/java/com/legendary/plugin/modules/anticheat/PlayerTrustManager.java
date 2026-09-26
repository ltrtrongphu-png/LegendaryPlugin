package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player trust scoring system - a premium anti-cheat feature that tracks
 * long-term player behaviour and adjusts detection sensitivity accordingly.
 *
 * Players accumulate trust over time through clean play (no violations),
 * and lose trust when flagged. High-trust players get slightly more
 * leniency (reduced flag weight), while low-trust players are scrutinized
 * more strictly (amplified flag weight). This dramatically reduces false
 * positives for established, trustworthy players while catching new
 * accounts that cheat from the start.
 *
 * Trust range: 0.0 (no trust) to 100.0 (full trust).
 * - New players start at 50.0 (neutral).
 * - Each clean hour online: +2 trust (capped at 100).
 * - Each violation: -trustLossPerFlag (scaled by violation weight).
 * - Players below 25 trust are flagged as "suspicious" in alerts.
 * - Players at 100 trust for 7+ days are "verified" (max leniency).
 *
 * The weight multiplier returned by {@link #getWeightMultiplier} is:
 *   - trust >= 80: 0.7 (30% leniency)
 *   - trust >= 60: 0.85 (15% leniency)
 *   - trust >= 40: 1.0 (neutral)
 *   - trust >= 20: 1.2 (20% amplified)
 *   - trust <  20: 1.5 (50% amplified)
 */
public final class PlayerTrustManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Double> trustScores = new ConcurrentHashMap<>();
    private final Map<UUID, Long> firstJoinTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTrustDecayTime = new ConcurrentHashMap<>();
    private BukkitTask trustTask;

    private double trustGainPerHour;
    private double trustLossPerFlag;
    private double minTrust;
    private double maxTrust;
    private double startingTrust;
    private long decayCheckIntervalTicks;

    public PlayerTrustManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        trustGainPerHour = plugin.getConfig().getDouble("anticheat.trust.gain-per-hour", 2.0);
        trustLossPerFlag = plugin.getConfig().getDouble("anticheat.trust.loss-per-flag", 5.0);
        minTrust = plugin.getConfig().getDouble("anticheat.trust.min", 0.0);
        maxTrust = plugin.getConfig().getDouble("anticheat.trust.max", 100.0);
        startingTrust = plugin.getConfig().getDouble("anticheat.trust.starting", 50.0);
        decayCheckIntervalTicks = plugin.getConfig().getLong("anticheat.trust.check-interval-seconds", 60) * 20L;
    }

    public void start() {
        stop();
        trustTask = Bukkit.getScheduler().runTaskTimer(plugin, this::trustTick, 20L * 30, decayCheckIntervalTicks);
    }

    public void stop() {
        if (trustTask != null) { trustTask.cancel(); trustTask = null; }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        firstJoinTime.putIfAbsent(uuid, System.currentTimeMillis());
        trustScores.putIfAbsent(uuid, startingTrust);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastTrustDecayTime.remove(event.getPlayer().getUniqueId());
    }

    /** Called from AnticheatModule.flag() - reduces trust when a player is flagged. */
    public void onViolation(Player player, double weight) {
        UUID uuid = player.getUniqueId();
        double current = trustScores.getOrDefault(uuid, startingTrust);
        double loss = trustLossPerFlag * Math.max(0.5, weight);
        trustScores.put(uuid, Math.max(minTrust, current - loss));
    }

    /**
     * Returns the weight multiplier for a player's flags based on their trust score.
     * Values < 1.0 = leniency (trusted player), > 1.0 = amplification (suspicious player).
     */
    public double getWeightMultiplier(Player player) {
        double trust = getTrust(player);
        if (trust >= 80) return 0.7;
        if (trust >= 60) return 0.85;
        if (trust >= 40) return 1.0;
        if (trust >= 20) return 1.2;
        return 1.5;
    }

    public double getTrust(Player player) {
        return trustScores.getOrDefault(player.getUniqueId(), startingTrust);
    }

    public String getTrustLabel(Player player) {
        double trust = getTrust(player);
        if (trust >= 80) return "Verified";
        if (trust >= 60) return "Trusted";
        if (trust >= 40) return "Neutral";
        if (trust >= 20) return "Suspicious";
        return "High-Risk";
    }

    public boolean isVerified(Player player) {
        return getTrust(player) >= 80;
    }

    public boolean isHighRisk(Player player) {
        return getTrust(player) < 20;
    }

    public long getDaysSinceFirstJoin(Player player) {
        Long joined = firstJoinTime.get(player.getUniqueId());
        if (joined == null) return 0;
        return (System.currentTimeMillis() - joined) / (1000L * 60 * 60 * 24);
    }

    public void setTrust(Player player, double value) {
        trustScores.put(player.getUniqueId(), Math.max(minTrust, Math.min(maxTrust, value)));
    }

    public void resetTrust(Player player) {
        trustScores.put(player.getUniqueId(), startingTrust);
    }

    private void trustTick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Long last = lastTrustDecayTime.get(uuid);
            long elapsed = last != null ? now - last : 0;
            if (elapsed >= 60_000) {
                double current = trustScores.getOrDefault(uuid, startingTrust);
                double gain = trustGainPerHour * (elapsed / 3_600_000.0);
                trustScores.put(uuid, Math.min(maxTrust, current + gain));
                lastTrustDecayTime.put(uuid, now);
            }
        }
    }

    public Map<UUID, Double> getAllTrustScores() {
        return Map.copyOf(trustScores);
    }
}
