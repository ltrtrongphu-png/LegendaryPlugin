package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags repeated near-instant armor equips (auto-armor hacks skip the manual drag-drop timing). */
public final class AutoArmorCheck extends Check implements Listener {

    private final Map<UUID, Long> lastChange = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> rapidCount = new ConcurrentHashMap<>();
    private long minIntervalMs;
    private int requiredCount;

    public AutoArmorCheck(AnticheatModule anticheat) {
        super(anticheat, "autoarmor", "AutoArmor");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minIntervalMs = ConfigUtil.getBoundedLong(plugin, path("min-interval-ms"), 150, 20, 2000);
        requiredCount = ConfigUtil.getBoundedInt(plugin, path("required-count"), 3, 2, 10);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long last = lastChange.put(uuid, now);
        if (last != null && now - last < minIntervalMs) {
            int count = rapidCount.merge(uuid, 1, Integer::sum);
            if (count >= requiredCount) {
                flag(player, 1.0, "rapidArmorChanges=" + count);
                rapidCount.put(uuid, 0);
            }
        } else {
            rapidCount.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastChange.remove(uuid);
        rapidCount.remove(uuid);
    }
}
