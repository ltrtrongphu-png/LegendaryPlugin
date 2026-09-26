package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags block placement faster than humanly possible (bridging/scaffold hacks). */
public final class ScaffoldCheck extends Check implements Listener {

    private final Map<UUID, Long> lastPlace = new ConcurrentHashMap<>();
    private long minIntervalMs;

    public ScaffoldCheck(AnticheatModule anticheat) {
        super(anticheat, "scaffold", "Scaffold");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minIntervalMs = ConfigUtil.getBoundedLong(plugin, path("min-interval-ms"), 45, 10, 500);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        long now = System.currentTimeMillis();
        Long last = lastPlace.put(player.getUniqueId(), now);
        if (last != null && now - last < minIntervalMs) {
            flag(player, 1.0, "interval=" + (now - last) + "ms");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastPlace.remove(event.getPlayer().getUniqueId());
    }
}
