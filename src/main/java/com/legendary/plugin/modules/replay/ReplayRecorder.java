package com.legendary.plugin.modules.replay;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a rolling in-memory buffer (last N seconds) of every online
 * player's position, and freezes a copy into a {@link ReplayClip} on
 * demand (e.g. when the anti-cheat flags someone) so staff can review
 * exactly what the player was doing right before the flag. Ported from
 * BaB.ReplayRecorder.
 *
 * The rolling {@code buffers} map is cleared per-player on quit (2026-09-20
 * leak fix) - a disconnected player has no "current" position to keep
 * buffering. {@code savedClips} is intentionally NOT cleared on quit:
 * those are the actual saved evidence staff review, often *because* the
 * player was kicked/banned and is no longer online - deleting them the
 * moment the player disconnects would defeat the point of saving them.
 */
public final class ReplayRecorder implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Deque<ReplaySnapshot>> buffers = new ConcurrentHashMap<>();
    private final Map<UUID, List<ReplayClip>> savedClips = new ConcurrentHashMap<>();
    private int bufferSeconds;
    private long snapshotIntervalTicks;
    private int maxClipsPerPlayer;
    private BukkitTask samplingTask;

    public ReplayRecorder(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        bufferSeconds = plugin.getConfig().getInt("replay.buffer-seconds", 15);
        snapshotIntervalTicks = plugin.getConfig().getLong("replay.snapshot-interval-ticks", 2);
        maxClipsPerPlayer = plugin.getConfig().getInt("replay.max-clips-per-player", 10);
    }

    public void start() {
        stop();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        samplingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 0L, snapshotIntervalTicks);
    }

    public void stop() {
        if (samplingTask != null) { samplingTask.cancel(); samplingTask = null; }
        org.bukkit.event.HandlerList.unregisterAll(this);
        buffers.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        buffers.remove(event.getPlayer().getUniqueId());
    }

    private void sample() {
        long cutoff = System.currentTimeMillis() - (bufferSeconds * 1000L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Deque<ReplaySnapshot> buffer = buffers.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
            synchronized (buffer) {
                buffer.addLast(ReplaySnapshot.of(player.getLocation()));
                while (!buffer.isEmpty() && buffer.peekFirst().timestamp() < cutoff) buffer.pollFirst();
            }
        }
    }

    public void saveClip(Player player, String triggeringCheck) {
        Deque<ReplaySnapshot> buffer = buffers.get(player.getUniqueId());
        if (buffer == null) return;
        List<ReplaySnapshot> copy;
        synchronized (buffer) {
            copy = new ArrayList<>(buffer);
        }
        ReplayClip clip = new ReplayClip(player.getUniqueId(), player.getName(), triggeringCheck, System.currentTimeMillis(), copy);
        List<ReplayClip> clips = savedClips.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        synchronized (clips) {
            clips.add(clip);
            while (clips.size() > maxClipsPerPlayer) clips.remove(0);
        }
    }

    public List<ReplayClip> getClips(UUID player) {
        List<ReplayClip> clips = savedClips.get(player);
        return clips == null ? List.of() : List.copyOf(clips);
    }
}
