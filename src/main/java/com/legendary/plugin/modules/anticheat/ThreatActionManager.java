package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodically scans online players for active cheating, feeds the
 * composite threat score into configurable automatic actions (kick at
 * high threat, ban at critical), and powers the /legendaryac threats
 * live dashboard. Runs on the main thread; all kick/ban calls are safe.
 * Pauses entirely during severe lag spikes to avoid false-positive
 * punishments caused by server-side TPS drops.
 *
 * Rapid-response mode: when any player is flagged, the scan interval
 * drops from the normal (default 10s) down to 1 tick (50ms) for a short
 * burst, so the system reacts to active cheating in near-real-time
 * rather than waiting up to the next scheduled scan. The burst lasts
 * for {@code rapidResponseDurationTicks} after the last flag, then
 * reverts to the normal interval.
 */
public final class ThreatActionManager implements Runnable {

    private final LegendaryPlugin plugin;
    private final HackProfileManager hackProfileManager;
    private final AnticheatModule anticheat;
    private org.bukkit.scheduler.BukkitTask task;

    private boolean enabled;
    private long scanIntervalTicks;
    private double kickThreshold;
    private double banThreshold;
    private String kickAction;
    private String banAction;

    private boolean rapidResponseEnabled;
    private long rapidResponseIntervalTicks;
    private long rapidResponseBurstTicks;
    private final AtomicLong lastFlagTick = new AtomicLong(0);
    private long currentTick = 0;

    private final ConcurrentHashMap<UUID, Long> recentlyFlagged = new ConcurrentHashMap<>();

    public ThreatActionManager(LegendaryPlugin plugin, AnticheatModule anticheat, HackProfileManager hackProfileManager) {
        this.plugin = plugin;
        this.anticheat = anticheat;
        this.hackProfileManager = hackProfileManager;
    }

    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("anticheat.threat-actions.enabled", true);
        scanIntervalTicks = plugin.getConfig().getLong("anticheat.threat-actions.scan-interval-seconds", 10) * 20L;
        kickThreshold = plugin.getConfig().getDouble("anticheat.threat-actions.kick-threshold", 60.0);
        banThreshold = plugin.getConfig().getDouble("anticheat.threat-actions.ban-threshold", 85.0);
        kickAction = plugin.getConfig().getString("anticheat.threat-actions.kick-message",
            "<red>You have been removed for suspicious activity. If you believe this is an error, contact staff.");
        banAction = plugin.getConfig().getString("anticheat.threat-actions.ban-message",
            "<red>Permanently banned by anti-cheat: confirmed cheating across multiple checks.");
        rapidResponseEnabled = plugin.getConfig().getBoolean("anticheat.threat-actions.rapid-response.enabled", true);
        rapidResponseIntervalTicks = plugin.getConfig().getLong("anticheat.threat-actions.rapid-response.interval-ticks", 1L);
        rapidResponseBurstTicks = plugin.getConfig().getLong("anticheat.threat-actions.rapid-response.burst-ticks", 200L);
    }

    public void start() {
        stop();
        if (!enabled) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, scanIntervalTicks, 1L);
        plugin.getLogger().info("Threat-action system active (normal scan every "
            + (scanIntervalTicks / 20) + "s, rapid-response every "
            + rapidResponseIntervalTicks + " tick(s) for "
            + (rapidResponseBurstTicks / 20) + "s after any flag, kick >= "
            + kickThreshold + ", ban >= " + banThreshold + ")");
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    /**
     * Called by AnticheatModule.flag() whenever any check fires. Triggers
     * rapid-response mode so the scanner runs at maximum speed for the
     * next burst window instead of waiting for the normal interval.
     */
    public void onFlag(UUID playerId) {
        if (!rapidResponseEnabled || !enabled) return;
        lastFlagTick.set(currentTick);
        recentlyFlagged.put(playerId, System.currentTimeMillis());
    }

    private boolean isRapidResponseActive() {
        if (!rapidResponseEnabled) return false;
        long ticksSinceFlag = currentTick - lastFlagTick.get();
        return ticksSinceFlag < rapidResponseBurstTicks;
    }

    @Override
    public void run() {
        currentTick++;
        if (anticheat.getTpsMonitor().isLagSpike()) {
            return;
        }
        if (!isRapidResponseActive() && currentTick % scanIntervalTicks != 0) {
            return;
        }
        long intervalTicks = isRapidResponseActive() ? rapidResponseIntervalTicks : scanIntervalTicks;
        if (isRapidResponseActive() && currentTick % intervalTicks != 0) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (anticheat.getBypassGuard().shouldBypass(player, "threat-scan")) continue;
            double score = hackProfileManager.getThreatScore(player);
            if (score >= banThreshold) {
                List<String> hacks = hackProfileManager.getActiveHacks(player);
                String reason = banAction + " (" + String.join(", ", hacks) + ")";
                anticheat.getBanExecutor().banDirect(player, reason, "permanent");
                plugin.getLogger().warning("[ThreatSystem] Banned " + player.getName()
                    + " (threat " + String.format("%.1f", score) + ", hacks: " + String.join(", ", hacks) + ")");
            } else if (score >= kickThreshold) {
                List<String> hacks = hackProfileManager.getActiveHacks(player);
                String reason = kickAction + " (threat " + String.format("%.0f", score) + ")";
                player.kick(Text.of(reason));
                plugin.getLogger().info("[ThreatSystem] Kicked " + player.getName()
                    + " (threat " + String.format("%.1f", score) + ", hacks: " + String.join(", ", hacks) + ")");
            }
        }
        long now = System.currentTimeMillis();
        recentlyFlagged.entrySet().removeIf(e -> now - e.getValue() > 60_000);
    }

    /**
     * Returns all currently online players who are actively cheating,
     * sorted by threat score (highest first). Used by /legendaryac threats.
     */
    public List<ThreatEntry> getThreatList() {
        List<ThreatEntry> entries = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (anticheat.getBypassGuard().shouldBypass(player, "threat-dashboard")) continue;
            double score = hackProfileManager.getThreatScore(player);
            if (score < 1.0) continue;
            String label = hackProfileManager.getThreatLabel(player);
            List<String> hacks = hackProfileManager.getActiveHacks(player);
            entries.add(new ThreatEntry(player.getName(), score, label, hacks));
        }
        entries.sort(Comparator.comparingDouble(ThreatEntry::score).reversed());
        return entries;
    }

    public boolean isRapidResponseMode() {
        return isRapidResponseActive();
    }

    public record ThreatEntry(String playerName, double score, String label, List<String> hacks) {}
}
