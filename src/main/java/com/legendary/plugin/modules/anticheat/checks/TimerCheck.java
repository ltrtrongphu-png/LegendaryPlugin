package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
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

/** Flags an abnormal number of movement packets within one second (Timer/speed-hack client tick manipulation). */
public final class TimerCheck extends Check implements Listener {

    private final Map<UUID, Deque<Long>> timestamps = new ConcurrentHashMap<>();
    private int maxMoves;

    public TimerCheck(AnticheatModule anticheat) {
        super(anticheat, "timer", "Timer");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxMoves = ConfigUtil.getBoundedInt(plugin, path("max-moves"), 24, 10, 100);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        long now = System.currentTimeMillis();
        Deque<Long> deque = timestamps.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && now - deque.peekFirst() > 1000) deque.pollFirst();
            double compensation = anticheat.getTpsMonitor().getCompensationFactor();
            if (deque.size() > maxMoves * compensation) {
                flag(player, 1.0, "moves/s=" + deque.size());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        timestamps.remove(event.getPlayer().getUniqueId());
    }
}
