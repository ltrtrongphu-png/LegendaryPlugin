package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Deque;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combined block-interaction rate detector. Tracks break+place actions
 * together in a sliding window to catch auto-builders and nukers that
 * individually stay below FastBreakCheck or FastPlaceCheck thresholds
 * but are obvious when combined. Also detects sustained burst patterns
 * (consistent high rate over multiple seconds = automation).
 */
public final class BlockRateCheck extends Check implements Listener {

    private final Map<UUID, Deque<Long>> breakTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> placeTimestamps = new ConcurrentHashMap<>();

    private int combinedMaxPerWindow;
    private int windowMs;
    private int sustainedBreakRate;
    private int sustainedPlaceRate;
    private int sustainedWindowMs;
    private double burstConsistencyThreshold;

    public BlockRateCheck(AnticheatModule anticheat) {
        super(anticheat, "blockrate", "BlockRate");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        combinedMaxPerWindow = plugin.getConfig().getInt(path("combined-max-per-window"), 30);
        windowMs = plugin.getConfig().getInt(path("window-ms"), 2000);
        sustainedBreakRate = plugin.getConfig().getInt(path("sustained-break-rate"), 15);
        sustainedPlaceRate = plugin.getConfig().getInt(path("sustained-place-rate"), 15);
        sustainedWindowMs = plugin.getConfig().getInt(path("sustained-window-ms"), 5000);
        burstConsistencyThreshold = plugin.getConfig().getDouble(path("burst-consistency-threshold"), 0.12);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Deque<Long> breaks = breakTimestamps.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (breaks) {
            breaks.addLast(now);
            while (!breaks.isEmpty() && now - breaks.peekFirst() > sustainedWindowMs) breaks.pollFirst();
        }
        checkCombined(player, uuid, now);
        checkSustained(player, uuid, breaks, sustainedBreakRate, "break");
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Deque<Long> places = placeTimestamps.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (places) {
            places.addLast(now);
            while (!places.isEmpty() && now - places.peekFirst() > sustainedWindowMs) places.pollFirst();
        }
        checkCombined(player, uuid, now);
        checkSustained(player, uuid, places, sustainedPlaceRate, "place");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        breakTimestamps.remove(uuid);
        placeTimestamps.remove(uuid);
    }

    private void checkCombined(Player player, UUID uuid, long now) {
        Deque<Long> breaks = breakTimestamps.get(uuid);
        Deque<Long> places = placeTimestamps.get(uuid);
        int breakCount = countInWindow(breaks, now, windowMs);
        int placeCount = countInWindow(places, now, windowMs);
        if (breakCount + placeCount > combinedMaxPerWindow) {
            flag(player, 1.0, String.format("combined=%d (break=%d place=%d) in %dms",
                breakCount + placeCount, breakCount, placeCount, windowMs));
        }
    }

    private void checkSustained(Player player, UUID uuid, Deque<Long> timestamps, int rateThreshold, String label) {
        if (timestamps == null || timestamps.size() < rateThreshold) return;
        long now = System.currentTimeMillis();
        long[] arr;
        synchronized (timestamps) {
            arr = timestamps.stream().filter(t -> now - t <= sustainedWindowMs)
                .mapToLong(Long::longValue).toArray();
        }
        if (arr.length < rateThreshold) return;
        long span = arr[arr.length - 1] - arr[0];
        if (span <= 0) return;
        double actualRate = arr.length / (span / 1000.0);
        if (actualRate >= rateThreshold) {
            double variance = 0;
            double sumIntervals = 0;
            int intervals = 0;
            for (int i = 1; i < arr.length; i++) {
                double interval = arr[i] - arr[i - 1];
                sumIntervals += interval;
                intervals++;
            }
            if (intervals > 0) {
                double mean = sumIntervals / intervals;
                for (int i = 1; i < arr.length; i++) {
                    double diff = (arr[i] - arr[i - 1]) - mean;
                    variance += diff * diff;
                }
                double stdDev = Math.sqrt(variance / intervals);
                double cv = mean > 0 ? stdDev / mean : 1.0;
                double weight = 1.0;
                if (cv < burstConsistencyThreshold) {
                    weight = 1.5;
                    flag(player, weight, String.format("sustained%s rate=%.1f/s cv=%.3f n=%d", label, actualRate, cv, arr.length));
                    return;
                }
                flag(player, weight, String.format("sustained%s rate=%.1f/s n=%d", label, actualRate, arr.length));
            }
        }
    }

    private int countInWindow(Deque<Long> deque, long now, int window) {
        if (deque == null) return 0;
        int count = 0;
        synchronized (deque) {
            for (Long t : deque) {
                if (now - t <= window) count++;
            }
        }
        return count;
    }
}
