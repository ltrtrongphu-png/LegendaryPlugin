package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects FastPlace / Tower hacks that place blocks faster than the
 * vanilla client allows. Vanilla has a minimum ~50ms interval between
 * block placements (limited by the client send-rate).
 *
 * Upgraded with:
 * - TPS compensation: raises the threshold when the server lags so
 *   lag-spiked placements don't false-positive.
 * - Placements-per-second window: tracks placements in a rolling 1s
 *   window and flags if the count exceeds maxPerSecond. This catches
 *   burst patterns that the interval check alone misses.
 * - Vertical tower detection: tracks consecutive placements where Y
 *   increases by ~1 each time, the classic Tower hack signature.
 */
public final class FastPlaceCheck extends Check implements Listener {

    private final Map<UUID, Long> lastPlaceTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> streak = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> placeTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> towerStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastPlaceY = new ConcurrentHashMap<>();
    private long minIntervalMs;
    private int requiredStreak;
    private int maxPerSecond;
    private int towerRequiredStreak;

    public FastPlaceCheck(AnticheatModule anticheat) {
        super(anticheat, "fastplace", "FastPlace");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minIntervalMs = plugin.getConfig().getLong(path("min-interval-ms"), 40);
        requiredStreak = plugin.getConfig().getInt(path("required-streak"), 4);
        maxPerSecond = plugin.getConfig().getInt(path("max-per-second"), 12);
        towerRequiredStreak = plugin.getConfig().getInt(path("tower-required-streak"), 5);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // --- Interval check with TPS compensation ---
        double tpsFactor = anticheat.getTpsMonitor().getCompensationFactor();
        long effectiveMin = (long) (minIntervalMs / tpsFactor);

        Long last = lastPlaceTime.get(uuid);
        lastPlaceTime.put(uuid, now);
        if (last != null) {
            long gap = now - last;
            if (gap < effectiveMin) {
                int s = streak.merge(uuid, 1, Integer::sum);
                if (s >= requiredStreak) {
                    flag(player, 1.0, String.format("placeGap=%dms (min=%dms) streak=%d", gap, effectiveMin, s));
                    streak.put(uuid, 0);
                }
            } else {
                streak.put(uuid, 0);
            }
        } else {
            streak.put(uuid, 0);
        }

        // --- Placements-per-second window ---
        Deque<Long> ts = placeTimestamps.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (ts) {
            ts.addLast(now);
            while (!ts.isEmpty() && now - ts.peekFirst() > 1000) ts.pollFirst();
            if (ts.size() > maxPerSecond) {
                flag(player, 1.5, String.format("places=%d/s (max=%d/s)", ts.size(), maxPerSecond));
                ts.clear();
            }
        }

        // --- Vertical tower detection ---
        double placeY = event.getBlock().getLocation().getY();
        Double prevY = lastPlaceY.put(uuid, placeY);
        if (prevY != null) {
            double dy = placeY - prevY;
            if (dy > 0.8 && dy < 1.3) {
                int t = towerStreak.merge(uuid, 1, Integer::sum);
                if (t >= towerRequiredStreak) {
                    flag(player, 2.0, String.format("towerStreak=%d dy=%.2f", t, dy));
                    towerStreak.put(uuid, 0);
                }
            } else {
                towerStreak.put(uuid, 0);
            }
        } else {
            towerStreak.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPlaceTime.remove(uuid);
        streak.remove(uuid);
        placeTimestamps.remove(uuid);
        towerStreak.remove(uuid);
        lastPlaceY.remove(uuid);
    }
}
