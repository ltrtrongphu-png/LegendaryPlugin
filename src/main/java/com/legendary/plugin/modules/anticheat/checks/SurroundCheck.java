package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
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
 * NEW CHECK (2026-09-19 DeepSeek-dump merge review). Complements
 * {@link ScaffoldCheck}, which only measures the *minimum interval*
 * between individual placements: Surround instead counts *total*
 * placements within a rolling one-second window, catching a "place a
 * block on every side of yourself simultaneously" hack (safewalk /
 * surround) where each individual placement might pass the min-interval
 * test but the burst count per second is still inhuman. Uses a small
 * violation buffer (streak of over-threshold seconds) to avoid flagging
 * a legitimate player quickly building a small structure.
 */
public final class SurroundCheck extends Check implements Listener {

    private final Map<UUID, Deque<Long>> placements = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> buffer = new ConcurrentHashMap<>();
    private int maxPerSecond;
    private int requiredBuffer;

    public SurroundCheck(AnticheatModule anticheat) {
        super(anticheat, "surround", "Surround");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxPerSecond = ConfigUtil.getBoundedInt(plugin, path("max-per-second"), 6, 2, 20);
        requiredBuffer = ConfigUtil.getBoundedInt(plugin, path("required-buffer"), 3, 1, 10);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Deque<Long> deque = placements.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        int size;
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && now - deque.peekFirst() > 1000) deque.pollFirst();
            size = deque.size();
        }
        if (size > maxPerSecond) {
            int count = buffer.merge(uuid, 1, Integer::sum);
            if (count >= requiredBuffer) {
                flag(player, 1.0, size + " blocks/s");
                buffer.put(uuid, 0);
            }
        } else {
            buffer.merge(uuid, -1, (a, b) -> Math.max(0, a + b));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        placements.remove(uuid);
        buffer.remove(uuid);
    }
}
