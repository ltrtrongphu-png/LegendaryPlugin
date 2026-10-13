package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tickshift: detects clients that manipulate their tick rate to gain
 * combat advantage. The pattern is a burst of fast ticks followed by
 * a stall (pause), creating a "tickshift" that lets the player attack
 * more rapidly for a short window. Detection: track inter-move-packet
 * intervals. A normal player has consistent ~50ms intervals. A tickshift
 * client shows a pattern of very short intervals (burst) followed by
 * abnormally long intervals (stall). We flag when the ratio of max to
 * min interval in a window exceeds the threshold.
 */
public final class TickshiftCheck extends Check implements Listener {

    private final Map<UUID, Deque<Long>> intervalHistory = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveTs = new ConcurrentHashMap<>();

    private int windowSize;
    private double maxBurstStallRatio;
    private long minStallMs;
    private long maxBurstMs;

    public TickshiftCheck(AnticheatModule anticheat) {
        super(anticheat, "tickshift", "Tickshift");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        windowSize = plugin.getConfig().getInt(path("window-size"), 20);
        maxBurstStallRatio = plugin.getConfig().getDouble(path("max-burst-stall-ratio"), 5.0);
        minStallMs = plugin.getConfig().getLong(path("min-stall-ms"), 200);
        maxBurstMs = plugin.getConfig().getLong(path("max-burst-ms"), 20);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long last = lastMoveTs.put(uuid, now);
        if (last == null) return;

        long interval = now - last;
        Deque<Long> history = intervalHistory.computeIfAbsent(uuid, k -> new ArrayDeque<>(windowSize));
        synchronized (history) {
            history.addLast(interval);
            while (history.size() > windowSize) history.pollFirst();

            if (history.size() >= windowSize) {
                long minInterval = Long.MAX_VALUE;
                long maxInterval = Long.MIN_VALUE;
                for (long iv : history) {
                    if (iv < minInterval) minInterval = iv;
                    if (iv > maxInterval) maxInterval = iv;
                }

                // Burst-stall pattern: some intervals very short (burst) and some very long (stall)
                if (maxInterval > minStallMs && minInterval < maxBurstMs) {
                    double ratio = (double) maxInterval / Math.max(1, minInterval);
                    if (ratio >= maxBurstStallRatio) {
                        flag(player, 1.0, String.format("burstStallRatio=%.1f min=%dms max=%dms",
                            ratio, minInterval, maxInterval));
                        history.clear();
                    }
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        intervalHistory.remove(uuid);
        lastMoveTs.remove(uuid);
    }
}
