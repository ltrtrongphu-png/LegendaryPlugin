package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ViolationManager implements Listener {

    private static final int MAX_TRACKED_PLAYERS = 10_000;
    private final LegendaryPlugin plugin;
    private final Map<UUID, Map<String, Double>> violations = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private final Map<FlagKey, Long> lastFlagAt = new ConcurrentHashMap<>();
    private final Map<FlagKey, Double> pendingWeights = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTouchedAt = new ConcurrentHashMap<>();
    private volatile long flagCooldownMillis;
    private volatile double decayPerMinute;
    private BukkitTask decayTask;

    public ViolationManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        flagCooldownMillis = Math.max(0L, plugin.getConfig().getLong("anticheat.flag-cooldown-ms", 1000L));
        decayPerMinute = Math.max(0.0, plugin.getConfig().getDouble("anticheat.vl-decay-per-minute", 0.0));
    }

    public void start() {
        stop();
        decayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::decay, 1200L, 1200L);
    }

    public void stop() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }

    /** Coalesces bursts from one check and player into at most one flag per cooldown. */
    public Double tryAddViolation(Player player, String check, double weight) {
        UUID uuid = player.getUniqueId();
        FlagKey key = new FlagKey(uuid, check);
        long now = System.currentTimeMillis();
        Long previous = lastFlagAt.get(key);
        if (previous != null && now - previous < flagCooldownMillis) {
            pendingWeights.merge(key, weight, Double::sum);
            return null;
        }

        lastFlagAt.put(key, now);
        double aggregatedWeight = weight + pendingWeights.getOrDefault(key, 0.0);
        pendingWeights.remove(key);
        return addViolation(player, check, aggregatedWeight);
    }

    public double addViolation(Player player, String check, double weight) {
        UUID uuid = player.getUniqueId();
        lastTouchedAt.put(uuid, System.currentTimeMillis());
        Object lock = locks.computeIfAbsent(uuid, ignored -> new Object());
        double newVl;
        synchronized (lock) {
            Map<String, Double> map = violations.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>());
            newVl = map.merge(check, weight, Double::sum);
        }
        saveLevel(uuid, check, newVl);
        trimOfflinePlayers();
        return newVl;
    }

    public double getVl(Player player, String check) {
        return getVl(player.getUniqueId(), check);
    }

    public double getVl(UUID uuid, String check) {
        Map<String, Double> map = violations.get(uuid);
        return map == null ? 0.0 : map.getOrDefault(check, 0.0);
    }

    public void resetCheck(Player player, String check) {
        UUID uuid = player.getUniqueId();
        Map<String, Double> map = violations.get(uuid);
        if (map != null) map.remove(check);
        saveLevel(uuid, check, 0);
    }

    public void resetAll(Player player) {
        resetAll(player.getUniqueId());
    }

    public void resetAll(UUID uuid) {
        Map<String, Double> removed;
        Object lock = locks.get(uuid);
        if (lock != null) {
            synchronized (lock) {
                removed = violations.remove(uuid);
            }
        } else {
            removed = violations.remove(uuid);
        }
        if (removed != null) removed.keySet().forEach(check -> saveLevel(uuid, check, 0));
        locks.remove(uuid);
        lastTouchedAt.remove(uuid);
        lastFlagAt.keySet().removeIf(key -> key.uuid().equals(uuid));
        pendingWeights.keySet().removeIf(key -> key.uuid().equals(uuid));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastTouchedAt.put(uuid, System.currentTimeMillis());
        DatabaseManager database = plugin.getAnticheatModule().getDatabaseManager();
        database.loadViolationLevelsAsync(uuid.toString(), stored -> {
            Object lock = locks.computeIfAbsent(uuid, ignored -> new Object());
            synchronized (lock) {
                Map<String, Double> levels = violations.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>());
                stored.forEach(levels::putIfAbsent);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastTouchedAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        trimOfflinePlayers();
    }

    private void decay() {
        if (decayPerMinute <= 0) return;
        for (Map.Entry<UUID, Map<String, Double>> player : violations.entrySet()) {
            UUID uuid = player.getKey();
            Object lock = locks.computeIfAbsent(uuid, ignored -> new Object());
            synchronized (lock) {
                player.getValue().replaceAll((check, vl) -> Math.max(0.0, vl - decayPerMinute));
                player.getValue().entrySet().removeIf(entry -> entry.getValue() == 0.0);
                player.getValue().forEach((check, vl) -> saveLevel(uuid, check, vl));
                if (Bukkit.getPlayer(uuid) == null && player.getValue().isEmpty()) {
                    violations.remove(uuid, player.getValue());
                }
            }
        }
    }

    private void saveLevel(UUID uuid, String check, double vl) {
        plugin.getAnticheatModule().getDatabaseManager()
            .saveViolationLevelAsync(uuid.toString(), check, vl);
    }

    private void trimOfflinePlayers() {
        int excess = violations.size() - MAX_TRACKED_PLAYERS;
        if (excess <= 0) return;
        lastTouchedAt.entrySet().stream()
            .filter(entry -> Bukkit.getPlayer(entry.getKey()) == null)
            .sorted(Map.Entry.comparingByValue())
            .limit(excess)
            .map(Map.Entry::getKey)
            .toList()
            .forEach(uuid -> {
                Map<String, Double> removed = violations.remove(uuid);
                locks.remove(uuid);
                lastTouchedAt.remove(uuid);
                if (removed != null) removed.keySet().forEach(check -> saveLevel(uuid, check, removed.get(check)));
            });
    }

    private record FlagKey(UUID uuid, String check) {}
}
