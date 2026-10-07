package com.legendary.plugin.modules.replay;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lets a staff member "watch" a saved {@link ReplayClip} by teleporting
 * their own camera through the recorded snapshots at the original
 * playback speed. Ported from BaB.ReplayPlaybackManager.
 *
 * FIXES (2026-09-20): the periodic teleport task never checked whether
 * the watching staff member was still online - if they disconnected
 * mid-playback, every subsequent tick would call teleport() on an
 * invalid Player object until the clip finished (repeated errors in the
 * console, and a leaked task). Now guarded with isOnline(), and a
 * PlayerQuitEvent handler stops/cleans up immediately on disconnect
 * instead of waiting for the clip to run out.
 */
public final class ReplayPlaybackManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, BukkitTask> activePlaybacks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Location> preWatchLocation = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean registered = false;

    public ReplayPlaybackManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private void ensureRegistered() {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    public void watch(Player staff, ReplayClip clip) {
        ensureRegistered();
        stop(staff);
        preWatchLocation.put(staff.getUniqueId(), staff.getLocation().clone());
        staff.setGameMode(org.bukkit.GameMode.SPECTATOR);

        int[] index = {0};
        BukkitTask[] taskHolder = new BukkitTask[1];
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!staff.isOnline()) {
                taskHolder[0].cancel();
                activePlaybacks.remove(staff.getUniqueId());
                preWatchLocation.remove(staff.getUniqueId());
                return;
            }
            if (index[0] >= clip.snapshots.size()) {
                stop(staff);
                return;
            }
            ReplaySnapshot snap = clip.snapshots.get(index[0]);
            World world = Bukkit.getWorld(snap.worldName());
            if (world != null) {
                staff.teleport(new Location(world, snap.x(), snap.y(), snap.z(), snap.yaw(), snap.pitch()));
            }
            index[0]++;
        }, 0L, 2L);
        activePlaybacks.put(staff.getUniqueId(), taskHolder[0]);
    }

    public void stop(Player staff) {
        BukkitTask task = activePlaybacks.remove(staff.getUniqueId());
        if (task != null) task.cancel();
        Location back = preWatchLocation.remove(staff.getUniqueId());
        if (back != null && staff.isOnline()) {
            staff.setGameMode(org.bukkit.GameMode.SPECTATOR.equals(staff.getGameMode()) ? org.bukkit.GameMode.SURVIVAL : staff.getGameMode());
            staff.teleport(back);
        }
    }

    public void stopAll() {
        for (BukkitTask task : activePlaybacks.values()) task.cancel();
        activePlaybacks.clear();
        preWatchLocation.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        BukkitTask task = activePlaybacks.remove(uuid);
        if (task != null) task.cancel();
        preWatchLocation.remove(uuid);
    }
}
