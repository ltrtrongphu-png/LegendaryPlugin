package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags an abnormal rate of right-click interactions in a sliding time window (interact-spam / macro). */
public final class FastInteractCheck extends Check implements Listener {

    private final Map<UUID, Deque<Long>> timestamps = new ConcurrentHashMap<>();
    private long windowMs;
    private int maxPerWindow;

    public FastInteractCheck(AnticheatModule anticheat) {
        super(anticheat, "fastinteract", "FastInteract");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        windowMs = ConfigUtil.getBoundedLong(plugin, path("window-ms"), 1000, 200, 5000);
        maxPerWindow = ConfigUtil.getBoundedInt(plugin, path("max-per-window"), 10, 2, 100);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        long now = System.currentTimeMillis();
        Deque<Long> deque = timestamps.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) deque.pollFirst();
            if (deque.size() > maxPerWindow) {
                flag(player, 1.0, deque.size() + "/" + windowMs + "ms");
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        timestamps.remove(event.getPlayer().getUniqueId());
    }
}
