package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pro v1.0.0 — detects rapid session cycling (join/quit spam) used by some
 * hacked clients to reset violation state or evade replay recording.
 */
public final class BadSessionsCheck extends Check implements Listener {

    private final java.util.Map<UUID, Deque<Long>> joinTimes = new ConcurrentHashMap<>();
    private long windowMs;
    private int maxSwitches;

    public BadSessionsCheck(AnticheatModule anticheat) {
        super(anticheat, "badsessions", "BadSessions");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        windowMs = plugin.getConfig().getLong(path("window-ms"), 60000L);
        maxSwitches = plugin.getConfig().getInt(path("max-session-switches"), 5);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        long now = System.currentTimeMillis();
        Deque<Long> times = joinTimes.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        times.addLast(now);
        prune(times, now);
        if (times.size() > maxSwitches) {
            flag(player, 5.0, "session-switches=" + times.size() + "/" + maxSwitches + " in " + windowMs + "ms");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        long now = System.currentTimeMillis();
        Deque<Long> times = joinTimes.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        times.addLast(now);
        prune(times, now);
        if (times.size() > maxSwitches) {
            flag(player, 5.0, "session-switches=" + times.size() + "/" + maxSwitches + " in " + windowMs + "ms");
        }
    }

    private void prune(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
            times.pollFirst();
        }
    }

    public void clearData(UUID uuid) {
        joinTimes.remove(uuid);
    }
}
