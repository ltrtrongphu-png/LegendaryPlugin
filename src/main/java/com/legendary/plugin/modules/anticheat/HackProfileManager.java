package com.legendary.plugin.modules.anticheat;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which hack modules a player is actively using and computes a
 * composite threat level from active hack count, total VL and player
 * trust. The threat level drives the live dashboard and automatic
 * protective actions (kick/ban) at extreme thresholds.
 */
public final class HackProfileManager {

    private static final long RECENT_WINDOW_MS = 60_000;
    private static final double ACTIVE_THRESHOLD = 1.0;

    private final ViolationManager violationManager;
    private final CheckRegistry checkRegistry;
    private PlayerTrustManager trustManager;
    private final Map<UUID, Map<String, Long>> lastFlagTime = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Double>> activeHackVls = new ConcurrentHashMap<>();

    public HackProfileManager(ViolationManager violationManager, CheckRegistry checkRegistry) {
        this.violationManager = violationManager;
        this.checkRegistry = checkRegistry;
    }

    void setTrustManager(PlayerTrustManager trustManager) {
        this.trustManager = trustManager;
    }

    void recordFlag(UUID uuid, String checkId) {
        lastFlagTime.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(checkId, System.currentTimeMillis());
    }

    public List<String> getActiveHacks(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, Long> flagTimes = lastFlagTime.get(uuid);
        if (flagTimes == null) return Collections.emptyList();

        long now = System.currentTimeMillis();
        Map<String, Double> active = new LinkedHashMap<>();
        Map<String, Double> vlCache = new LinkedHashMap<>();

        for (Map.Entry<String, Long> entry : flagTimes.entrySet()) {
            String checkId = entry.getKey();
            long flagTime = entry.getValue();
            if (now - flagTime > RECENT_WINDOW_MS) continue;

            double vl = violationManager.getVl(player, checkId);
            if (vl < ACTIVE_THRESHOLD) continue;

            String display = checkRegistry.get(checkId)
                .map(Check::getDisplayName)
                .orElse(checkId);
            active.put(display, vl);
            vlCache.put(display, vl);
        }

        activeHackVls.put(uuid, vlCache);

        List<String> result = new ArrayList<>(active.keySet());
        result.sort((a, b) -> Double.compare(active.get(b), active.get(a)));
        return result;
    }

    public double getThreatScore(Player player) {
        List<String> active = getActiveHacks(player);
        if (active.isEmpty()) return 0.0;

        UUID uuid = player.getUniqueId();
        Map<String, Double> vls = activeHackVls.getOrDefault(uuid, Collections.emptyMap());
        double totalVl = vls.values().stream().mapToDouble(Double::doubleValue).sum();
        int hackCount = active.size();

        double hackComponent = Math.min(40, hackCount * 10.0);
        double vlComponent = Math.min(35, totalVl * 0.5);
        double trustComponent = 0;
        if (trustManager != null) {
            double trust = trustManager.getTrust(player);
            trustComponent = Math.min(25, (50.0 - trust) * 0.5);
        }
        return Math.max(0, Math.min(100, hackComponent + vlComponent + trustComponent));
    }

    public String getThreatLabel(Player player) {
        double score = getThreatScore(player);
        if (score >= 80) return "CRITICAL";
        if (score >= 60) return "HIGH";
        if (score >= 40) return "MODERATE";
        if (score >= 20) return "LOW";
        return "MINIMAL";
    }

    public String getThreatColor(double score) {
        if (score >= 80) return "<dark_red>";
        if (score >= 60) return "<red>";
        if (score >= 40) return "<gold>";
        if (score >= 20) return "<yellow>";
        return "<green>";
    }

    public boolean isCheating(Player player) {
        return !getActiveHacks(player).isEmpty();
    }

    public int getActiveHackCount(Player player) {
        return getActiveHacks(player).size();
    }

    public void clear(UUID uuid) {
        lastFlagTime.remove(uuid);
        activeHackVls.remove(uuid);
    }
}
