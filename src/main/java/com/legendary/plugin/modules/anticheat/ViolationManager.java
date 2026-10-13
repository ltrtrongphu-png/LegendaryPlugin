package com.legendary.plugin.modules.anticheat;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ViolationManager implements Listener {

    private final Map<UUID, Map<String, Double>> violations = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public ViolationManager(com.legendary.plugin.LegendaryPlugin plugin) {
    }

    public void loadConfigValues() {
    }

    public void start() {
    }

    public void stop() {
    }

    public double addViolation(Player player, String check, double weight) {
        UUID uuid = player.getUniqueId();
        Object lock = locks.computeIfAbsent(uuid, k -> new Object());
        synchronized (lock) {
            Map<String, Double> map = violations.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
            double newVl = map.merge(check, weight, Double::sum);
            return newVl;
        }
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
        Object lock = locks.get(uuid);
        if (lock != null) {
            synchronized (lock) {
                violations.remove(uuid);
            }
        } else {
            violations.remove(uuid);
        }
        locks.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        resetAll(event.getPlayer().getUniqueId());
    }
}
