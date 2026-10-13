package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClickDistribution: analyzes the statistical distribution of click
 * intervals to detect autoclickers that add jitter to bypass simple
 * stddev checks. Instead of just checking stddev, this check computes
 * the Kolmogorov-Smirnov-like statistic against a uniform distribution
 * of intervals. Human clicks follow a roughly log-normal distribution;
 * autoclickers with jitter produce distributions that are too uniform
 * or have artificial clustering. We flag when the distribution is too
 * "flat" (low entropy) or too "spiky" (bimodal).
 */
public final class ClickDistributionCheck extends Check implements Listener {

    private final Map<UUID, Deque<Long>> clickIntervals = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClickTs = new ConcurrentHashMap<>();

    private int minSamples;
    private double minEntropy;
    private double maxBimodality;
    private long windowMs;

    public ClickDistributionCheck(AnticheatModule anticheat) {
        super(anticheat, "clickdistribution", "ClickDistribution");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minSamples = plugin.getConfig().getInt(path("min-samples"), 30);
        minEntropy = plugin.getConfig().getDouble(path("min-entropy"), 0.5);
        maxBimodality = plugin.getConfig().getDouble(path("max-bimodality"), 0.3);
        windowMs = plugin.getConfig().getLong(path("window-ms"), 5000);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long last = lastClickTs.put(uuid, now);
        if (last == null) return;

        long interval = now - last;
        Deque<Long> intervals = clickIntervals.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (intervals) {
            intervals.addLast(interval);
            while (!intervals.isEmpty() && now - (lastClickTs.get(uuid) != null ? now - intervals.size() * 50 : 0) > windowMs) {
                intervals.pollFirst();
            }
            // Trim to window by count
            while (intervals.size() > 100) intervals.pollFirst();

            if (intervals.size() >= minSamples) {
                double entropy = computeEntropy(intervals);
                double bimodality = computeBimodality(intervals);

                if (entropy < minEntropy || bimodality > maxBimodality) {
                    flag(player, 1.0, String.format("entropy=%.2f bimodality=%.2f samples=%d",
                        entropy, bimodality, intervals.size()));
                    intervals.clear();
                }
            }
        }
    }

    /**
     * Computes normalized entropy of click intervals. Low entropy indicates
     * a mechanical pattern (too predictable). Human clicks have high entropy.
     */
    private double computeEntropy(Deque<Long> intervals) {
        double[] arr = intervals.stream().mapToDouble(Long::doubleValue).toArray();
        if (arr.length < 2) return 1.0;
        double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        for (double v : arr) { if (v < min) min = v; if (v > max) max = v; }
        if (max - min < 1) return 0.0;

        int buckets = 10;
        int[] hist = new int[buckets];
        for (double v : arr) {
            int idx = (int) ((v - min) / (max - min) * (buckets - 1));
            hist[idx]++;
        }
        double entropy = 0;
        int n = arr.length;
        for (int h : hist) {
            if (h == 0) continue;
            double p = (double) h / n;
            entropy -= p * Math.log(p);
        }
        return entropy / Math.log(buckets);
    }

    /**
     * Computes a simple bimodality coefficient. High values indicate
     * the distribution has two peaks (suspicious for jitter autoclickers
     * that alternate between two fixed intervals).
     */
    private double computeBimodality(Deque<Long> intervals) {
        double[] arr = intervals.stream().mapToDouble(Long::doubleValue).toArray();
        if (arr.length < 4) return 0.0;
        double sum = 0;
        for (double v : arr) sum += v;
        double mean = sum / arr.length;
        double s2 = 0, s3 = 0, s4 = 0;
        for (double v : arr) {
            double d = v - mean;
            s2 += d * d;
            s3 += d * d * d;
            s4 += d * d * d * d;
        }
        double variance = s2 / arr.length;
        if (variance < 1) return 0.0;
        double skewness = (s3 / arr.length) / Math.pow(variance, 1.5);
        double kurtosis = (s4 / arr.length) / (variance * variance) - 3;
        double n = arr.length;
        return (skewness * skewness + 1) / (kurtosis + 3 * (n - 1) * (n - 1) / ((n - 2) * (n - 3)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clickIntervals.remove(uuid);
        lastClickTs.remove(uuid);
    }
}
