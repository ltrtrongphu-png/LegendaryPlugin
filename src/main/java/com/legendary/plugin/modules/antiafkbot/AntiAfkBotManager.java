package com.legendary.plugin.modules.antiafkbot;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.LanguageManager;
import com.legendary.plugin.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
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
    private final ConcurrentHashMap<UUID, Position> lastLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> warnedPlayers = new ConcurrentHashMap<>();
    private List<String> exemptWorlds = List.of();

    private record Position(String world, double x, double y, double z, float yaw, float pitch) {}

    private BukkitTask sweepTask;
    private long sweepIntervalSeconds = 20;
    private long idleThresholdSeconds = 300;
    private long warnSeconds = 60;
    private boolean kickBroadcast = true;
    private boolean enabled = false;

    public AntiAfkBotManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        var cfg = plugin.getConfig();
        sweepIntervalSeconds = Math.max(10, cfg.getLong("anti-afk-bot.sweep-interval-seconds", 20));
        idleThresholdSeconds = cfg.getLong("anti-afk-bot.idle-threshold-seconds", 300);
        warnSeconds = cfg.getLong("anti-afk-bot.warn-seconds", 60);
        kickBroadcast = cfg.getBoolean("anti-afk-bot.kick-broadcast", true);
        exemptWorlds = List.copyOf(cfg.getStringList("anti-afk-bot.exempt-worlds"));
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
                if (isExemptFromKick(player)) {
                    recordActivity(player.getUniqueId());
                    continue;
                }
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

    public void recordMovement(UUID uuid, org.bukkit.Location newLoc) {
        Position next = new Position(newLoc.getWorld().getName(), newLoc.getX(), newLoc.getY(),
            newLoc.getZ(), newLoc.getYaw(), newLoc.getPitch());
        Position old = lastLocation.get(uuid);
        if (old != null && old.world().equals(next.world())) {
            double dx = old.x() - next.x();
            double dy = old.y() - next.y();
            double dz = old.z() - next.z();
            float yawDelta = Math.abs((old.yaw() - next.yaw() + 540) % 360 - 180);
            float pitchDelta = Math.abs(old.pitch() - next.pitch());
            if (dx * dx + dy * dy + dz * dz > 0.25 || yawDelta >= 15 || pitchDelta >= 15) {
                recordActivity(uuid);
            }
        }
        lastLocation.compute(uuid, (ignored, previous) -> {
            if (previous == null || !previous.world().equals(next.world())
                || squaredDistance(previous, next) > 0.25
                || angleDelta(previous.yaw(), next.yaw()) >= 15
                || Math.abs(previous.pitch() - next.pitch()) >= 15) {
                return next;
            }
            return previous;
        });
    }

    private static double squaredDistance(Position a, Position b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static float angleDelta(float a, float b) {
        return Math.abs((a - b + 540) % 360 - 180);
    }

    public void removePlayer(UUID uuid) {
        lastActivity.remove(uuid);
        lastLocation.remove(uuid);
        warnedPlayers.remove(uuid);
    }

    public boolean isEnabled() { return enabled; }

    public int getTrackedCount() { return lastActivity.size(); }

    boolean isExemptFromKick(Player player) {
        return player.hasPermission("legendary.antiafkbot.bypass")
            || player.isInsideVehicle()
            || exemptWorlds.contains(player.getWorld().getName());
    }
}
