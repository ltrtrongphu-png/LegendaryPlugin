package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EscalationManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Map<String, Integer>> appliedThresholds = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> recentHighConfidenceFlags = new ConcurrentHashMap<>();
    private final Set<String> warnedLegacyConfig = ConcurrentHashMap.newKeySet();
    private volatile List<Threshold> thresholds = List.of(
        new Threshold(10.0, "alert"), new Threshold(25.0, "kick"), new Threshold(50.0, "ban"));
    private volatile boolean safeMode = true;
    private volatile boolean dryRun = true;
    private volatile long consensusWindowMillis = 600_000L;

    public EscalationManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        List<Threshold> loaded = new ArrayList<>();
        for (Map<?, ?> entry : plugin.getConfig().getMapList("anticheat.escalation.thresholds")) {
            Object rawVl = entry.get("at-vl");
            Object rawAction = entry.get("action");
            if (!(rawVl instanceof Number value) || !(rawAction instanceof String action)) continue;
            if (!Set.of("alert", "kick", "ban").contains(action.toLowerCase())) continue;
            if (value.doubleValue() > 0) loaded.add(new Threshold(value.doubleValue(), action.toLowerCase()));
        }
        if (!loaded.isEmpty()) {
            loaded.sort(Comparator.comparingDouble(Threshold::atVl));
            thresholds = List.copyOf(loaded);
        } else {
            thresholds = List.of(new Threshold(10.0, "alert"), new Threshold(25.0, "kick"),
                new Threshold(50.0, "ban"));
        }
        safeMode = plugin.getConfig().getBoolean("anticheat.safe-mode", true);
        dryRun = plugin.getConfig().getBoolean("anticheat.ban.dry-run", true);
        consensusWindowMillis = Math.max(60_000L,
            plugin.getConfig().getLong("anticheat.ban.consensus-window-seconds", 600L) * 1000L);
        appliedThresholds.clear();
        recentHighConfidenceFlags.clear();
        warnedLegacyConfig.clear();
    }

    public void escalate(Player player, String checkId, String displayName, double vl,
                         Check.Confidence confidence) {
        warnForLegacyConfig(checkId);
        long now = System.currentTimeMillis();
        if (confidence == Check.Confidence.HIGH) {
            recentHighConfidenceFlags.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .put(checkId, now);
        }
        Map<String, Integer> playerLevels =
            appliedThresholds.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        int applied = playerLevels.getOrDefault(checkId, -1);
        int highest = -1;
        for (int i = 0; i < thresholds.size(); i++) {
            if (vl >= thresholds.get(i).atVl()) highest = i;
        }
        if (highest <= applied) return;
        playerLevels.put(checkId, highest);

        String action = thresholds.get(highest).action();
        if (confidence == Check.Confidence.LOW && !action.equals("alert")) {
            action = "alert";
        }
        switch (action) {
            case "alert" -> plugin.getMetrics().counter("anticheat.escalation.alerts").increment();
            case "kick" -> {
                if (safeMode || confidence == Check.Confidence.LOW) {
                    plugin.getLogger().warning("[AntiCheat safe-mode] Suppressed kick for "
                        + player.getName() + " (" + checkId + ", VL " + vl + ")");
                    return;
                }
                plugin.getMetrics().counter("anticheat.escalation.kicks").increment();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.kick(com.legendary.plugin.util.Text.of("<red>Kicked by anti-cheat: " + displayName));
                    }
                });
            }
            case "ban" -> attemptBan(player, checkId, displayName, vl, confidence, now);
            default -> plugin.getLogger().warning("Unknown escalation action: " + action);
        }
    }

    private void attemptBan(Player player, String checkId, String displayName, double vl,
                            Check.Confidence confidence, long now) {
        if (confidence != Check.Confidence.HIGH || safeMode || dryRun
            || !hasHighConfidenceConsensus(player.getUniqueId(), now)) {
            plugin.getLogger().warning("[AntiCheat safe-mode] Suppressed ban for " + player.getName()
                + " (" + checkId + ", VL " + vl + ", confidence " + confidence + ")");
            return;
        }
        plugin.getMetrics().counter("anticheat.escalation.bans").increment();
        double currentVl = plugin.getAnticheatModule().getViolationManager().getVl(player, checkId);
        plugin.getAnticheatModule().getBanExecutor().ban(player, displayName, currentVl);
        plugin.getAnticheatModule().getDatabaseManager().recordBanAsync(
            player.getUniqueId().toString(), player.getName(), checkId, currentVl,
            "AutoBan: " + displayName + " (VL " + String.format("%.1f", currentVl) + ")");
    }

    private boolean hasHighConfidenceConsensus(UUID uuid, long now) {
        Map<String, Long> flags = recentHighConfidenceFlags.get(uuid);
        if (flags == null) return false;
        flags.entrySet().removeIf(entry -> now - entry.getValue() > consensusWindowMillis);
        return flags.size() >= 2;
    }

    private void warnForLegacyConfig(String checkId) {
        String value = plugin.getConfig().getString("anticheat.checks." + checkId + ".escalation");
        if (value != null && value.contains(",") && warnedLegacyConfig.add(checkId)) {
            plugin.getLogger().warning("Legacy escalation list for '" + checkId
                + "' is deprecated; configure anticheat.escalation.thresholds instead.");
        }
    }

    public void resetPlayer(UUID uuid) {
        appliedThresholds.remove(uuid);
        recentHighConfidenceFlags.remove(uuid);
    }

    private record Threshold(double atVl, String action) {}
}
