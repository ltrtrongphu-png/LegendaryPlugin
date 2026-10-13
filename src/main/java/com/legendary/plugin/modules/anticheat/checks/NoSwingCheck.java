package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags hitting an entity without a preceding arm-swing animation packet (Killaura signature: "NoSwing"). */
public final class NoSwingCheck extends Check implements Listener {

    private final Map<UUID, Long> lastSwing = new ConcurrentHashMap<>();
    private long maxGapMs;

    public NoSwingCheck(AnticheatModule anticheat) {
        super(anticheat, "noswing", "NoSwing");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxGapMs = ConfigUtil.getBoundedLong(plugin, path("max-gap-ms"), 200, 50, 2000);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        lastSwing.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (shouldSkip(player)) return;
        Long last = lastSwing.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (last == null || now - last > maxGapMs) {
            flag(player, 1.0, "gap=" + (last == null ? "never" : (now - last) + "ms"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSwing.remove(event.getPlayer().getUniqueId());
    }
}
