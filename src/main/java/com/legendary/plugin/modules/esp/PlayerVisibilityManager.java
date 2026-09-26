package com.legendary.plugin.modules.esp;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.util.RaycastUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides players from each other's client when there is no line of sight
 * and they are beyond a safety margin, defeating ESP/x-ray-style client
 * mods that render entities through walls. Ported from
 * AntiESPUltimate.PlayerVisibilityModule; packet-level dependency on
 * ProtocolLib removed in favor of the vanilla Bukkit
 * hidePlayer/showPlayer API which is sufficient for this purpose and
 * keeps the module usable even without ProtocolLib installed.
 */
public final class PlayerVisibilityManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Set<UUID>> hiddenByViewer = new ConcurrentHashMap<>();
    private final Map<UUID, Team> teamCache = new ConcurrentHashMap<>();
    private volatile long lastTeamRefresh = 0;

    private long checkIntervalTicks;
    private double maxDistance;
    private double minDistance;
    private double blockingThreshold;
    private boolean teamExemption;
    private int maxPlayersForRaycast;
    private BukkitTask task;

    public PlayerVisibilityManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        checkIntervalTicks = ConfigUtil.getBoundedLong(plugin, "esp.player-visibility.check-interval-ticks", 10, 2, 200);
        maxDistance = ConfigUtil.getBoundedDouble(plugin, "esp.player-visibility.max-distance", 48.0, 8.0, 256.0);
        minDistance = ConfigUtil.getBoundedDouble(plugin, "esp.player-visibility.min-distance", 4.0, 0.0, maxDistance);
        blockingThreshold = ConfigUtil.getBoundedDouble(plugin, "esp.player-visibility.blocking-threshold", 0.55, 0.0, 1.0);
        teamExemption = plugin.getConfig().getBoolean("esp.player-visibility.team-exemption", true);
        maxPlayersForRaycast = ConfigUtil.getBoundedInt(plugin, "esp.player-visibility.max-players-for-raycast", 40, 5, 300);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("esp.player-visibility.enabled", true)) return;
        stop();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, checkIntervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
        // Restore visibility for everyone so nobody stays permanently hidden.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (viewer != target) viewer.showPlayer(plugin, target);
            }
        }
        hiddenByViewer.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hiddenByViewer.remove(uuid);          // this player's own "who I'm hiding" set
        teamCache.remove(uuid);
        for (Set<UUID> hiddenSet : hiddenByViewer.values()) {
            hiddenSet.remove(uuid);            // remove them as a hidden target from everyone else
        }
    }

    private void tick() {
        refreshTeamsIfStale();
        var online = Bukkit.getOnlinePlayers();
        // Above this many concurrent players, the O(n^2) pairwise raycast (each doing a real
        // world block trace) becomes expensive enough to hurt TPS on its own - fall back to a
        // cheaper distance-only check instead of skipping the module entirely.
        boolean useRaycast = online.size() <= maxPlayersForRaycast;
        for (Player viewer : online) {
            if (viewer.hasPermission("legendary.esp.bypass")) continue;
            Set<UUID> hiddenNow = hiddenByViewer.computeIfAbsent(viewer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            for (Player target : online) {
                if (viewer == target) continue;
                boolean shouldHide = evaluate(viewer, target, useRaycast);
                boolean currentlyHidden = hiddenNow.contains(target.getUniqueId());
                if (shouldHide && !currentlyHidden) {
                    viewer.hidePlayer(plugin, target);
                    hiddenNow.add(target.getUniqueId());
                } else if (!shouldHide && currentlyHidden) {
                    viewer.showPlayer(plugin, target);
                    hiddenNow.remove(target.getUniqueId());
                }
            }
        }
    }

    private boolean evaluate(Player viewer, Player target, boolean useRaycast) {
        if (teamExemption && sameTeam(viewer, target)) return false;
        if (!viewer.getWorld().equals(target.getWorld())) return true;
        double distSq = viewer.getLocation().distanceSquared(target.getLocation());
        if (distSq <= minDistance * minDistance) return false; // always visible up close
        if (distSq > maxDistance * maxDistance) return true;   // too far, no need to render
        if (!useRaycast) return distSq > (maxDistance * 0.5) * (maxDistance * 0.5); // distance-only fallback
        return !RaycastUtils.hasLineOfSight(
            viewer.getEyeLocation(), target.getEyeLocation(), blockingThreshold);
    }

    private boolean sameTeam(Player a, Player b) {
        Team ta = teamCache.get(a.getUniqueId());
        Team tb = teamCache.get(b.getUniqueId());
        return ta != null && ta.equals(tb);
    }

    private void refreshTeamsIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastTeamRefresh < 5000) return;
        lastTeamRefresh = now;
        var scoreboard = Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
        if (scoreboard == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Team team = scoreboard.getEntryTeam(p.getName());
            if (team != null) teamCache.put(p.getUniqueId(), team);
            else teamCache.remove(p.getUniqueId());
        }
    }
}
