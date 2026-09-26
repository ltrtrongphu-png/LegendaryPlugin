package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags block-break events happening faster than possible given the tool/block hardness (simplified: min interval). */
public final class FastBreakCheck extends Check implements Listener {

    private final Map<UUID, Long> lastBreak = new ConcurrentHashMap<>();
    private long minIntervalMs;

    public FastBreakCheck(AnticheatModule anticheat) {
        super(anticheat, "fastbreak", "FastBreak");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minIntervalMs = ConfigUtil.getBoundedLong(plugin, path("min-interval-ms"), 30, 5, 500);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        long now = System.currentTimeMillis();
        Long last = lastBreak.put(player.getUniqueId(), now);
        if (last != null && now - last < minIntervalMs) {
            flag(player, 1.0, "interval=" + (now - last) + "ms");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastBreak.remove(event.getPlayer().getUniqueId());
    }
}
