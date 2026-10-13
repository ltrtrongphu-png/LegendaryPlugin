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
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
    private final AtomicInteger totalSnapshots = new AtomicInteger();
    private int bufferSeconds;
    private long snapshotIntervalTicks;
    private int maxClipsPerPlayer;
    private int maxTotalSnapshots;
    private BukkitTask samplingTask;

    public ReplayRecorder(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        bufferSeconds = plugin.getConfig().getInt("replay.buffer-seconds", 15);
        snapshotIntervalTicks = plugin.getConfig().getLong("replay.snapshot-interval-ticks", 2);
        maxClipsPerPlayer = plugin.getConfig().getInt("replay.max-clips-per-player", 10);
        maxTotalSnapshots = Math.max(1000, plugin.getConfig().getInt("replay.max-total-snapshots", 100_000));
    }

    public void start() {
        stop();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        samplingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 0L, snapshotIntervalTicks);
    }

    public void stop() {
        if (samplingTask != null) { samplingTask.cancel(); samplingTask = null; }
        org.bukkit.event.HandlerList.unregisterAll(this);
        for (Deque<ReplaySnapshot> buffer : buffers.values()) {
            synchronized (buffer) {
                totalSnapshots.addAndGet(-buffer.size());
            }
        }
        buffers.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Deque<ReplaySnapshot> removed = buffers.remove(event.getPlayer().getUniqueId());
        if (removed != null) {
            synchronized (removed) {
                totalSnapshots.addAndGet(-removed.size());
            }
        }
    }

    private void sample() {
        long cutoff = System.currentTimeMillis() - (bufferSeconds * 1000L);
        int maxSnapshots = bufferSeconds * (int) Math.max(1, (20 / snapshotIntervalTicks)) + 1;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Deque<ReplaySnapshot> buffer = buffers.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>(maxSnapshots));
            synchronized (buffer) {
                buffer.addLast(ReplaySnapshot.of(player.getLocation()));
                totalSnapshots.incrementAndGet();
                while (!buffer.isEmpty() && buffer.peekFirst().timestamp() < cutoff) {
                    buffer.pollFirst();
                    totalSnapshots.decrementAndGet();
                }
            }
        }
        enforceSnapshotBudget();
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
            totalSnapshots.addAndGet(copy.size());
            while (clips.size() > maxClipsPerPlayer) {
                ReplayClip removed = clips.remove(0);
                totalSnapshots.addAndGet(-removed.snapshots.size());
            }
        }
        persistClipAsync(clip);
        enforceSnapshotBudget();
    }

    public List<ReplayClip> getClips(UUID player) {
        List<ReplayClip> clips = savedClips.get(player);
        return clips == null ? List.of() : List.copyOf(clips);
    }

    private void enforceSnapshotBudget() {
        while (totalSnapshots.get() > maxTotalSnapshots) {
            Deque<ReplaySnapshot> oldestBuffer = null;
            long oldestAt = Long.MAX_VALUE;
            for (Deque<ReplaySnapshot> buffer : buffers.values()) {
                synchronized (buffer) {
                    ReplaySnapshot first = buffer.peekFirst();
                    if (first != null && first.timestamp() < oldestAt) {
                        oldestAt = first.timestamp();
                        oldestBuffer = buffer;
                    }
                }
            }
            if (oldestBuffer != null) {
                synchronized (oldestBuffer) {
                    if (!oldestBuffer.isEmpty()) {
                        oldestBuffer.removeFirst();
                        totalSnapshots.decrementAndGet();
                        continue;
                    }
                }
            }

            if (!removeOldestClip()) break;
        }
    }

    private boolean removeOldestClip() {
        List<ReplayClip> owner = null;
        ReplayClip oldest = null;
        for (List<ReplayClip> clips : savedClips.values()) {
            synchronized (clips) {
                if (!clips.isEmpty() && (oldest == null || clips.get(0).recordedAt < oldest.recordedAt)) {
                    owner = clips;
                    oldest = clips.get(0);
                }
            }
        }
        if (owner == null) return false;
        synchronized (owner) {
            if (owner.isEmpty()) return false;
            ReplayClip removed = owner.remove(0);
            totalSnapshots.addAndGet(-removed.snapshots.size());
            return true;
        }
    }

    private void persistClipAsync(ReplayClip clip) {
        String check = clip.triggeringCheck.replaceAll("[^A-Za-z0-9_.-]", "_");
        Path path = plugin.getDataFolder().toPath().resolve("replays")
            .resolve(clip.player.toString()).resolve(clip.recordedAt + "-" + check + ".json");
        StringBuilder json = new StringBuilder("{\"player\":\"").append(clip.player)
            .append("\",\"name\":\"").append(escape(clip.playerName))
            .append("\",\"check\":\"").append(escape(clip.triggeringCheck))
            .append("\",\"recordedAt\":").append(clip.recordedAt).append(",\"snapshots\":[");
        for (int i = 0; i < clip.snapshots.size(); i++) {
            ReplaySnapshot snapshot = clip.snapshots.get(i);
            if (i > 0) json.append(',');
            json.append("{\"timestamp\":").append(snapshot.timestamp())
                .append(",\"world\":\"").append(escape(snapshot.worldName()))
                .append("\",\"x\":").append(snapshot.x()).append(",\"y\":").append(snapshot.y())
                .append(",\"z\":").append(snapshot.z()).append(",\"yaw\":").append(snapshot.yaw())
                .append(",\"pitch\":").append(snapshot.pitch()).append('}');
        }
        json.append("]}\n");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, json.toString(), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            } catch (java.io.IOException exception) {
                plugin.getLogger().warning("Could not persist replay clip for " + clip.player + ": "
                    + exception.getMessage());
            }
        });
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
