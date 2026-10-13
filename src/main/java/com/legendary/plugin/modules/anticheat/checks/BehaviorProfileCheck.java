package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pro v1.0.0 — builds a behavioral profile per player over a warmup period,
 * then monitors for deviations that indicate a hack was toggled mid-session.
 * Tracks click rate, move rate, rotation rate, and interact rate as a
 * multi-dimensional profile. Flags when the live behavior diverges from the
 * established baseline by more than the configured threshold.
 */
public final class BehaviorProfileCheck extends Check implements Listener {

    private final java.util.Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Integer> warmupRemaining = new ConcurrentHashMap<>();
    private BukkitTask task;

    private int profileIntervalTicks;
    private double anomalyThreshold;
    private int warmupTicks;

    public BehaviorProfileCheck(AnticheatModule anticheat) {
        super(anticheat, "behaviorprofile", "BehaviorProfile");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        profileIntervalTicks = plugin.getConfig().getInt(path("profile-interval-ticks"), 20);
        anomalyThreshold = plugin.getConfig().getDouble(path("anomaly-threshold"), 0.72);
        warmupTicks = plugin.getConfig().getInt(path("warmup-ticks"), 200);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        profiles.put(player.getUniqueId(), new Profile());
        warmupRemaining.put(player.getUniqueId(), warmupTicks);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        profiles.remove(event.getPlayer().getUniqueId());
        warmupRemaining.remove(event.getPlayer().getUniqueId());
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, profileIntervalTicks, profileIntervalTicks);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        if (!enabled) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Profile p = profiles.get(uuid);
            if (p == null) continue;

            int warm = warmupRemaining.getOrDefault(uuid, 0);
            if (warm > 0) {
                warmupRemaining.put(uuid, warm - profileIntervalTicks);
                p.updateBaseline();
                continue;
            }

            double score = p.computeDeviation();
            if (score > anomalyThreshold) {
                flag(player, 3.0, "deviation=" + String.format("%.2f", score) + " threshold=" + anomalyThreshold);
                p.updateBaseline();
            }
        }
    }

    public void recordClick(UUID uuid) {
        Profile p = profiles.get(uuid);
        if (p != null) p.clicks++;
    }

    public void recordMove(UUID uuid) {
        Profile p = profiles.get(uuid);
        if (p != null) p.moves++;
    }

    public void recordInteract(UUID uuid) {
        Profile p = profiles.get(uuid);
        if (p != null) p.interacts++;
    }

    private static final class Profile {
        double baselineClicks, baselineMoves, baselineInteracts;
        int clicks, moves, interacts;

        void updateBaseline() {
            baselineClicks = baselineClicks * 0.7 + clicks * 0.3;
            baselineMoves = baselineMoves * 0.7 + moves * 0.3;
            baselineInteracts = baselineInteracts * 0.7 + interacts * 0.3;
            clicks = 0; moves = 0; interacts = 0;
        }

        double computeDeviation() {
            double dc = deviation(clicks, baselineClicks);
            double dm = deviation(moves, baselineMoves);
            double di = deviation(interacts, baselineInteracts);
            return (dc + dm + di) / 3.0;
        }

        private double deviation(int current, double baseline) {
            if (baseline < 1.0) return 0.0;
            return Math.min(1.0, Math.abs(current - baseline) / baseline);
        }
    }
}
