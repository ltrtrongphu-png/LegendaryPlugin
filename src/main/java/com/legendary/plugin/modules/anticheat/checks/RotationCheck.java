package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import com.legendary.plugin.util.StatsUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Flags unnaturally uniform head-rotation deltas (aim/rotation bots produce very low variance vs. a human's shaky aim). */
public final class RotationCheck extends Check implements Listener {

    private static final long WINDOW_MS = 1500;
    private static final int MIN_SAMPLES = 15;
    private static final double MIN_DELTA_TO_COUNT = 0.05;

    private final Map<java.util.UUID, Deque<double[]>> rotationDeltaSamples = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, float[]> lastRotation = new ConcurrentHashMap<>();
    private boolean uniformityEnabled;
    private boolean snapEnabled;
    private double minAvgDegPerTick;
    private double maxCv;
    private double maxRotationPerTick;

    public RotationCheck(AnticheatModule anticheat) {
        super(anticheat, "rotation", "RotationUniformity");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        uniformityEnabled = plugin.getConfig().getBoolean(path("uniformity-enabled"), true);
        snapEnabled = plugin.getConfig().getBoolean(path("snap-enabled"), true);
        minAvgDegPerTick = plugin.getConfig().getDouble(path("min-avg-deg-per-tick"), 0.15);
        maxCv = plugin.getConfig().getDouble(path("max-cv"), 0.08);
        maxRotationPerTick = plugin.getConfig().getDouble(path("max-rotation-per-tick"), 180.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        float yaw = event.getTo().getYaw();
        float pitch = event.getTo().getPitch();
        float[] last = lastRotation.put(player.getUniqueId(), new float[]{yaw, pitch});
        if (last == null) return;
        double yawDelta = Math.abs(normalize(yaw - last[0]));
        double pitchDelta = Math.abs(pitch - last[1]);
        double delta = Math.max(yawDelta, pitchDelta);
        if (delta < MIN_DELTA_TO_COUNT) return;

        // Snap check is independent of uniformity check - needs its own guard
        if (snapEnabled && delta > maxRotationPerTick) {
            flag(player, 1.0, String.format("rotationSnap=%.1f (max=%.1f)", delta, maxRotationPerTick));
            return;
        }

        if (!uniformityEnabled) return;

        long now = System.currentTimeMillis();
        Deque<double[]> samples = rotationDeltaSamples.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        synchronized (samples) {
            samples.addLast(new double[]{now, delta});
            while (!samples.isEmpty() && now - samples.peekFirst()[0] > WINDOW_MS) samples.pollFirst();
            if (samples.size() < MIN_SAMPLES) return;
            double[] values = samples.stream().mapToDouble(s -> s[1]).toArray();
            StatsUtil.Result stats = StatsUtil.coefficientOfVariation(values);
            if (stats.mean() >= minAvgDegPerTick && stats.coefficientOfVariation() <= maxCv) {
                flag(player, 0.75, String.format("mean=%.2f cv=%.3f", stats.mean(), stats.coefficientOfVariation()));
                samples.clear();
            }
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        rotationDeltaSamples.remove(uuid);
        lastRotation.remove(uuid);
    }

    private double normalize(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
