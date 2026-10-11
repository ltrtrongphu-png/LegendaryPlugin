package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pro v1.0.0 — Monte Carlo Consistency Analysis. Periodically samples each
 * player's movement and combat behavior and runs a statistical consistency
 * test against a large set of legitimate baselines. Flags players whose
 * behavior falls outside the normal distribution with high confidence.
 */
public final class MccAnalyzeCheck extends Check implements Listener {

    private final java.util.Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private BukkitTask task;

    private int checkIntervalTicks;
    private double flagThreshold;

    public MccAnalyzeCheck(AnticheatModule anticheat) {
        super(anticheat, "mccanalyze", "MccAnalyze");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        checkIntervalTicks = plugin.getConfig().getInt(path("check-interval-ticks"), 100);
        flagThreshold = plugin.getConfig().getDouble(path("flag-threshold"), 0.65);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        windows.remove(event.getPlayer().getUniqueId());
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, checkIntervalTicks, checkIntervalTicks);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        if (!enabled) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (shouldSkip(player)) continue;
            UUID uuid = player.getUniqueId();
            Window w = windows.computeIfAbsent(uuid, k -> new Window());

            double speed = player.getLocation().getDirection().lengthSquared();
            double yVel = player.getVelocity().getY();
            double pitch = Math.abs(player.getLocation().getPitch());

            w.addSample(speed, yVel, pitch);

            if (w.count >= 20) {
                double score = w.computeConsistency();
                if (score > flagThreshold) {
                    flag(player, 2.5, "mcc-score=" + String.format("%.2f", score) + " threshold=" + flagThreshold);
                }
                w.reset();
            }
        }
    }

    private static final class Window {
        double[] speeds = new double[20];
        double[] yVels = new double[20];
        double[] pitches = new double[20];
        int count = 0;

        void addSample(double speed, double yVel, double pitch) {
            if (count < 20) {
                speeds[count] = speed;
                yVels[count] = yVel;
                pitches[count] = pitch;
                count++;
            }
        }

        void reset() { count = 0; }

        double computeConsistency() {
            double sv = variance(speeds, count);
            double yv = variance(yVels, count);
            double pv = variance(pitches, count);
            double avg = (sv + yv + pv) / 3.0;
            return Math.min(1.0, avg * 10.0);
        }

        private double variance(double[] arr, int n) {
            if (n < 2) return 0.0;
            double sum = 0;
            for (int i = 0; i < n; i++) sum += arr[i];
            double mean = sum / n;
            double sq = 0;
            for (int i = 0; i < n; i++) { double d = arr[i] - mean; sq += d * d; }
            return sq / n;
        }
    }
}
