package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only monitoring layer that periodically scans online players,
 * computes their composite threat score, and powers the /legendaryac
 * threats live dashboard plus console alerting for high-threat players.
 *
 * NOTE: This class is NOT an auto-punisher. Punishment (kick/ban) is
 * handled exclusively by {@link EscalationManager} via the per-check
 * escalation ladder (alert, alert, alert, kick, ban). ThreatActionManager
 * only observes and reports — it never kicks or bans a player. This
 * avoids two independent systems acting on the same player at once.
 *
 * Runs on the main thread. Pauses entirely during severe lag spikes to
 * avoid false-positive readings caused by server-side TPS drops.
 *
 * Rapid-response mode: when any player is flagged, the scan interval
 * drops from the normal (default 10s) down to 1 tick (50ms) for a short
 * burst, so the system reacts to active cheating in near-real-time
 * rather than waiting up to the next scheduled scan. The burst lasts
 * for {@code rapidResponseBurstTicks} after the last flag, then
 * reverts to the normal interval.
 */
public final class ThreatActionManager implements Runnable {

    private final LegendaryPlugin plugin;
    private final HackProfileManager hackProfileManager;
    private final AnticheatModule anticheat;
    private org.bukkit.scheduler.BukkitTask task;

    private boolean enabled;
    private long scanIntervalTicks;
    private double alertThreshold;

    private boolean rapidResponseEnabled;
    private long rapidResponseIntervalTicks;
    private long rapidResponseBurstTicks;
    private final AtomicLong lastFlagTick = new AtomicLong(0);
    private volatile long currentTick = 0;

    public ThreatActionManager(LegendaryPlugin plugin, AnticheatModule anticheat, HackProfileManager hackProfileManager) {
        this.plugin = plugin;
        this.anticheat = anticheat;
        this.hackProfileManager = hackProfileManager;
    }

    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("anticheat.threat-actions.enabled", true);
        scanIntervalTicks = plugin.getConfig().getLong("anticheat.threat-actions.scan-interval-seconds", 10) * 20L;
        alertThreshold = plugin.getConfig().getDouble("anticheat.threat-actions.alert-threshold", 60.0);
        rapidResponseEnabled = plugin.getConfig().getBoolean("anticheat.threat-actions.rapid-response.enabled", true);
        rapidResponseIntervalTicks = plugin.getConfig().getLong("anticheat.threat-actions.rapid-response.interval-ticks", 5L);
        rapidResponseBurstTicks = plugin.getConfig().getLong("anticheat.threat-actions.rapid-response.burst-ticks", 100L);
    }

    public void start() {
        stop();
        if (!enabled) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, scanIntervalTicks, 1L);
        plugin.getLogger().info("Threat-action monitoring active (normal scan every "
            + (scanIntervalTicks / 20) + "s, rapid-response every "
            + rapidResponseIntervalTicks + " tick(s) for "
            + (rapidResponseBurstTicks / 20) + "s after any flag, alert >= "
            + alertThreshold + "). Punishment is handled by EscalationManager.");
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
            if (score >= alertThreshold) {
                List<String> hacks = hackProfileManager.getActiveHacks(player);
                plugin.getLogger().warning("[ThreatSystem] High threat: " + player.getName()
                    + " (score " + String.format("%.1f", score) + ", hacks: " + String.join(", ", hacks) + ")");
            }
        }
    }

    /**
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
