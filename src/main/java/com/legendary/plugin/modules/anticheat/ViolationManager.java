package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player, per-check violation-level (VL) counters.
 * VL does NOT decay over time - if a player was flagged at VL 40/60 and
 * then stops cheating, the VL stays at 40 until manually reset or the
 * player relogs. This prevents a cheater from simply waiting out their
 * violations to reset their record.
 *
 * Entries are cleared on quit (prevents unbounded memory growth on
 * servers with high player turnover). The permanent violation history
 * is preserved in {@link DatabaseManager}.
 */
public final class ViolationManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Map<String, Double>> violations = new ConcurrentHashMap<>();

    public ViolationManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        // No decay configuration - VL is permanent until reset or relog.
    }

    public void start() {
        // No periodic tasks needed without decay.
    }

    public void stop() {
        // Nothing to stop.
    }

    public double addViolation(Player player, String check, double weight) {
        UUID uuid = player.getUniqueId();
        Map<String, Double> map = violations.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        double newVl = map.merge(check, weight, Double::sum);
        return newVl;
    }

    public double getVl(Player player, String check) {
        Map<String, Double> map = violations.get(player.getUniqueId());
        return map == null ? 0.0 : map.getOrDefault(check, 0.0);
    }

    public void resetCheck(Player player, String check) {
        Map<String, Double> map = violations.get(player.getUniqueId());
        if (map != null) map.remove(check);
    }

    public void resetAll(Player player) {
        resetAll(player.getUniqueId());
    }

    public void resetAll(UUID uuid) {
        violations.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        resetAll(event.getPlayer().getUniqueId());
    }
}
