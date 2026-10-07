package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pro v1.0.0 — fingerprints the statistical distribution of player packet
 * timing intervals. Legitimate players produce naturally varying intervals;
 * hacked clients (timer, blink, packet batching) produce distributions with
 * abnormally low variance or bimodal peaks. Flags when the anomaly score
 * exceeds the configured threshold.
 */
public final class PacketFingerprintCheck extends Check implements Listener {

    private final java.util.Map<UUID, double[]> intervalBuffers = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> lastMoveTs = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Integer> sampleCounts = new ConcurrentHashMap<>();

    private int sampleSize;
    private double anomalyThreshold;

    public PacketFingerprintCheck(AnticheatModule anticheat) {
        super(anticheat, "packetfingerprint", "PacketFingerprint");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        sampleSize = plugin.getConfig().getInt(path("sample-size"), 100);
        anomalyThreshold = plugin.getConfig().getDouble(path("anomaly-threshold"), 0.35);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (event.getTo() == null) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastMoveTs.get(uuid);
        lastMoveTs.put(uuid, now);
        if (last == null) return;

        double interval = now - last;
        double[] buf = intervalBuffers.computeIfAbsent(uuid, k -> new double[sampleSize]);
        int count = sampleCounts.getOrDefault(uuid, 0);
        int idx = count % sampleSize;
        buf[idx] = interval;
        sampleCounts.put(uuid, count + 1);

        if (count + 1 >= sampleSize) {
            double score = computeAnomaly(buf);
            if (score > anomalyThreshold) {
                flag(player, 4.0, "anomaly=" + String.format("%.2f", score) + " threshold=" + anomalyThreshold);
            }
        }
    }

    private double computeAnomaly(double[] samples) {
        double sum = 0, sumSq = 0;
        int n = 0;
        for (double s : samples) {
            if (s <= 0) continue;
            sum += s;
            sumSq += s * s;
            n++;
        }
        if (n < 10) return 0.0;
        double mean = sum / n;
        double variance = (sumSq / n) - (mean * mean);
        double cv = mean > 0 ? Math.sqrt(Math.max(0, variance)) / mean : 0.0;
        return Math.max(0.0, 1.0 - cv);
    }

    public void clearData(UUID uuid) {
        intervalBuffers.remove(uuid);
        lastMoveTs.remove(uuid);
        sampleCounts.remove(uuid);
    }
}
