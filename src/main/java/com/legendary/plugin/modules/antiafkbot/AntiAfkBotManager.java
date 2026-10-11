package com.legendary.plugin.modules.antiafkbot;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.LanguageManager;
import com.legendary.plugin.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player activity and kicks idle players on a periodic schedule.
 * Activity is measured by last movement, interaction, or chat timestamp.
 *
 * Two scheduling modes:
 *   1. Periodic sweep: every N seconds, check all players and kick those
 *      idle longer than the threshold. This is the "check every 1 hour"
 *      mode — the sweep runs at the configured interval, and within each
 *      sweep, only players idle beyond idle-threshold-seconds are kicked.
 *   2. Warning: a configurable warning is sent warn-seconds before the
 *      kick so genuine players can react.
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class AntiAfkBotManager {

    private final LegendaryPlugin plugin;
    private final ConcurrentHashMap<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> warnedPlayers = new ConcurrentHashMap<>();

    private BukkitTask sweepTask;
    private long sweepIntervalSeconds = 3600;
    private long idleThresholdSeconds = 300;
    private long warnSeconds = 60;
    private boolean kickBroadcast = true;
    private boolean enabled = false;

    public AntiAfkBotManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        var cfg = plugin.getConfig();
        sweepIntervalSeconds = cfg.getLong("anti-afk-bot.sweep-interval-seconds", 3600);
        idleThresholdSeconds = cfg.getLong("anti-afk-bot.idle-threshold-seconds", 300);
        warnSeconds = cfg.getLong("anti-afk-bot.warn-seconds", 60);
        kickBroadcast = cfg.getBoolean("anti-afk-bot.kick-broadcast", true);
    }

    public void start() {
        if (sweepTask != null) sweepTask.cancel();

        long intervalTicks = Math.max(20L, sweepIntervalSeconds * 20L);
        long warnTicks = Math.max(20L, warnSeconds * 20L);

        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            long idleMs = idleThresholdSeconds * 1000L;
            long warnMs = warnSeconds * 1000L;
            int checked = 0;
            int kicked = 0;

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("legendary.antiafkbot.bypass")) continue;
                checked++;

                UUID uuid = player.getUniqueId();
                Long last = lastActivity.get(uuid);
                if (last == null) {
                    last = now;
                    lastActivity.put(uuid, last);
                }

                long idleTime = now - last;
                if (idleTime < idleMs - warnMs) {
                    warnedPlayers.remove(uuid);
                    continue;
                }

                if (idleTime >= idleMs) {
                    String kickMsg = plugin.getLanguageManager().get("antiafkbot.kick-message");
                    player.kick(Text.of(kickMsg));
                    warnedPlayers.remove(uuid);
                    kicked++;
                    if (kickBroadcast) {
                        String broadcast = plugin.getLanguageManager().get("antiafkbot.kick-broadcast",
                            "player", player.getName());
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (p.hasPermission("legendary.anticheat.alerts")) {
                                Text.send(p, plugin.getLanguageManager().get("antiafkbot.prefix") + broadcast);
                            }
                        }
                    }
                    plugin.getLogger().info("[AntiAfkBot] Kicked " + player.getName() + " (idle " + (idleTime / 1000) + "s)");
                } else if (!warnedPlayers.getOrDefault(uuid, false)) {
                    warnedPlayers.put(uuid, true);
                    String warn = "<yellow>You will be kicked for inactivity in " + warnSeconds + "s. Move to cancel.";
                    Text.send(player, plugin.getLanguageManager().get("antiafkbot.prefix") + warn);
                }
            }

            if (checked > 0) {
                plugin.getLogger().info("[AntiAfkBot] Sweep complete: checked " + checked + ", kicked " + kicked);
            }
        }, intervalTicks, intervalTicks);

        enabled = true;
        plugin.getLogger().info("[AntiAfkBot] Active — sweep every " + sweepIntervalSeconds
            + "s, idle threshold " + idleThresholdSeconds + "s, warning at " + warnSeconds + "s.");
    }

    public void stop() {
        if (sweepTask != null) { sweepTask.cancel(); sweepTask = null; }
        lastActivity.clear();
        lastLocation.clear();
        warnedPlayers.clear();
        enabled = false;
    }

    public void recordActivity(UUID uuid) {
        lastActivity.put(uuid, System.currentTimeMillis());
        warnedPlayers.remove(uuid);
    }

    public void recordMovement(UUID uuid, Location newLoc) {
        Location old = lastLocation.get(uuid);
        if (old != null && old.getWorld() == newLoc.getWorld()) {
            double distSq = old.distanceSquared(newLoc);
            if (distSq > 0.25) {
                recordActivity(uuid);
            }
        }
        lastLocation.put(uuid, newLoc.clone());
    }

    public void removePlayer(UUID uuid) {
        lastActivity.remove(uuid);
        lastLocation.remove(uuid);
        warnedPlayers.remove(uuid);
    }

    public boolean isEnabled() { return enabled; }

    public int getTrackedCount() { return lastActivity.size(); }
}
