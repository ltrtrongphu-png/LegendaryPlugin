package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flags a hit whose yaw/pitch snapped to perfectly aligned with the
 * target between the previous tick and the hit tick - a signature of
 * "silent aim" / aim-assist hacks that only rotate the hitbox
 * calculation, not the rendered camera. Ported from BaB.AimAssistCheck.
 *
 * Upgraded with GCD (Greatest Common Divisor) sensitivity analysis:
 * aimbot clients use a fixed mouse-sensitivity value that produces
 * rotation deltas whose GCD is a constant or near-zero. A real human
 * mouse always has sensor noise, so the GCD of consecutive rotation
 * deltas varies. By computing the GCD of the last N yaw deltas, we can
 * detect mechanical aim with very high confidence.
 */
public final class AimAssistCheck extends Check implements Listener {

    private final Map<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastPitch = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Double>> yawDeltaHistory = new ConcurrentHashMap<>();
    private double minSnapDegrees;
    private double minPitchSnapDegrees;
    private double alignmentThreshold;
    private boolean gcdEnabled;
    private int gcdMinSamples;
    private double gcdMaxValue;

    public AimAssistCheck(AnticheatModule anticheat) {
        super(anticheat, "aimassist", "AimAssist");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minSnapDegrees = plugin.getConfig().getDouble(path("min-snap-degrees"), 40.0);
        minPitchSnapDegrees = plugin.getConfig().getDouble(path("min-pitch-snap-degrees"), 25.0);
        alignmentThreshold = plugin.getConfig().getDouble(path("alignment-threshold"), 0.97);
        gcdEnabled = plugin.getConfig().getBoolean(path("gcd-enabled"), true);
        gcdMinSamples = plugin.getConfig().getInt(path("gcd-min-samples"), 20);
        gcdMaxValue = plugin.getConfig().getDouble(path("gcd-max-value"), 0.5);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity target)) return;
        if (shouldSkip(attacker)) return;

        Vector toTarget = target.getEyeLocation().toVector().subtract(attacker.getEyeLocation().toVector());
        if (toTarget.lengthSquared() < 1e-6) return;
        toTarget.normalize();
        Vector look = attacker.getEyeLocation().getDirection().normalize();
        double dot = toTarget.dot(look);

        float yawNow = attacker.getLocation().getYaw();
        float pitchNow = attacker.getLocation().getPitch();
        Float yawBefore = lastYaw.put(attacker.getUniqueId(), yawNow);
        Float pitchBefore = lastPitch.put(attacker.getUniqueId(), pitchNow);
        if (yawBefore == null || pitchBefore == null) return;

        double yawDelta = Math.abs(normalize(yawNow - yawBefore));
        double pitchDelta = Math.abs(pitchNow - pitchBefore);

        if (dot >= alignmentThreshold && (yawDelta >= minSnapDegrees || pitchDelta >= minPitchSnapDegrees)) {
            flag(attacker, 1.0, String.format("dot=%.3f yawSnap=%.1f pitchSnap=%.1f", dot, yawDelta, pitchDelta));
        }

        if (gcdEnabled) {
            checkGcd(attacker, yawDelta);
        }
    }

    /**
     * GCD sensitivity analysis: aimbot clients produce rotation deltas
     * whose GCD converges to a fixed value (the client's mouse sensitivity
     * constant). A real human's mouse sensor noise prevents the GCD from
     * ever stabilizing. If the GCD of the last N yaw deltas is a positive
     * constant below the threshold, the player is using a fixed-sensitivity
     * aimbot.
     */
    private void checkGcd(Player player, double yawDelta) {
        if (yawDelta < 0.1) return;
        UUID uuid = player.getUniqueId();
        Deque<Double> history = yawDeltaHistory.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(yawDelta);
            while (history.size() > gcdMinSamples * 2) history.pollFirst();
            if (history.size() < gcdMinSamples) return;
            double[] deltas = history.stream().mapToDouble(Double::doubleValue).toArray();
            double gcd = computeGcd(deltas);
            if (gcd > 0.001 && gcd < gcdMaxValue) {
                long roundedCount = 0;
                for (double d : deltas) {
                    if (Math.abs(d - gcd * Math.round(d / gcd)) < 0.01) roundedCount++;
                }
                if (roundedCount > deltas.length * 0.85) {
                    flag(player, 1.5, String.format("gcd=%.4f roundedRatio=%.2f", gcd, (double) roundedCount / deltas.length));
                    history.clear();
                }
            }
        }
    }

    private static double computeGcd(double[] values) {
        double result = values[0];
        for (int i = 1; i < values.length && result > 0.001; i++) {
            result = gcd(result, values[i]);
        }
        return result;
    }

    private static double gcd(double a, double b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b > 0.001) {
            double t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    private double normalize(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastYaw.remove(uuid);
        lastPitch.remove(uuid);
        yawDeltaHistory.remove(uuid);
    }
}
