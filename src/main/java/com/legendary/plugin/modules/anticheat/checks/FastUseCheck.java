package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags eating/drinking faster than the item's minimum vanilla consume time. */
public final class FastUseCheck extends Check implements Listener {

    private final Map<UUID, Long> lastConsume = new ConcurrentHashMap<>();
    private long minIntervalMs;

    public FastUseCheck(AnticheatModule anticheat) {
        super(anticheat, "fastuse", "FastUse");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minIntervalMs = ConfigUtil.getBoundedLong(plugin, path("min-interval-ms"), 400, 100, 3000);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        long now = System.currentTimeMillis();
        Long last = lastConsume.put(player.getUniqueId(), now);
        if (last != null && now - last < minIntervalMs) {
            flag(player, 1.0, "interval=" + (now - last) + "ms");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastConsume.remove(event.getPlayer().getUniqueId());
    }
}
