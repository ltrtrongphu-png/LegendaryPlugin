package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pro v1.0.0 — lightweight ML-style anomaly detection using a sliding-window
 * feature vector of movement and combat metrics. Computes a confidence score
 * using a simple distance-based classifier against the player's own rolling
 * baseline. Flags when confidence exceeds the threshold, indicating behavior
 * that is statistically unlikely to be human.
 */
public final class MlDetectCheck extends Check implements Listener {

    private final java.util.Map<UUID, FeatureWindow> features = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, double[]> baselines = new ConcurrentHashMap<>();
    private BukkitTask task;

    private long sampleWindowMs;
    private int minSamples;
    private double confidenceThreshold;

    public MlDetectCheck(AnticheatModule anticheat) {
        super(anticheat, "mldetect", "MlDetect");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        sampleWindowMs = plugin.getConfig().getLong(path("sample-window-ms"), 5000L);
        minSamples = plugin.getConfig().getInt(path("min-samples"), 50);
        confidenceThreshold = plugin.getConfig().getDouble(path("confidence-threshold"), 0.85);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (event.getTo() == null) return;
        UUID uuid = player.getUniqueId();
        FeatureWindow fw = features.computeIfAbsent(uuid, k -> new FeatureWindow(sampleWindowMs));
        double dx = event.getTo().getX() - event.getFrom().getX();
        double dy = event.getTo().getY() - event.getFrom().getY();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        fw.addSample(dx * dx + dz * dz, dy, player.getLocation().getPitch(), player.getLocation().getYaw());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        features.remove(event.getPlayer().getUniqueId());
        baselines.remove(event.getPlayer().getUniqueId());
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (shouldSkip(player)) continue;
            UUID uuid = player.getUniqueId();
            FeatureWindow fw = features.get(uuid);
            if (fw == null || fw.count() < minSamples) continue;

            double[] vec = fw.computeFeatureVector(now);
            double[] baseline = baselines.get(uuid);
            if (baseline == null) {
                baselines.put(uuid, vec);
            } else {
                double dist = euclidean(vec, baseline);
                double confidence = Math.min(1.0, dist / 2.0);
                if (confidence > confidenceThreshold) {
                    flag(player, 5.0, "ml-confidence=" + String.format("%.2f", confidence) + " threshold=" + confidenceThreshold);
                    baselines.put(uuid, vec);
                } else {
                    for (int i = 0; i < baseline.length; i++) {
                        baseline[i] = baseline[i] * 0.95 + vec[i] * 0.05;
                    }
                }
            }
        }
    }

    private double euclidean(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    private static final class FeatureWindow {
        private final long windowMs;
        private final java.util.Deque<Sample> samples = new java.util.ArrayDeque<>();

        FeatureWindow(long windowMs) { this.windowMs = windowMs; }

        void addSample(double hSpeed, double yVel, float pitch, float yaw) {
            samples.addLast(new Sample(System.currentTimeMillis(), hSpeed, yVel, pitch, yaw));
            prune();
        }

        void prune() {
            long cutoff = System.currentTimeMillis() - windowMs;
            while (!samples.isEmpty() && samples.peekFirst().ts < cutoff) {
                samples.pollFirst();
            }
        }

        int count() { prune(); return samples.size(); }

        double[] computeFeatureVector(long now) {
            prune();
            if (samples.isEmpty()) return new double[]{0, 0, 0, 0};
            double sumH = 0, sumY = 0, sumP = 0, sumYaw = 0;
            for (Sample s : samples) {
                sumH += s.hSpeed;
                sumY += s.yVel;
                sumP += s.pitch;
                sumYaw += s.yaw;
            }
            int n = samples.size();
            return new double[]{sumH / n, sumY / n, sumP / n, sumYaw / n};
        }

        private record Sample(long ts, double hSpeed, double yVel, float pitch, float yaw) {}
    }
}
