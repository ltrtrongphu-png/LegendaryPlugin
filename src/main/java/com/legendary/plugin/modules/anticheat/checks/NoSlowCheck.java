package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags moving faster than the "slowed" cap while eating/blocking/using a bow (NoSlow hacks). */
public final class NoSlowCheck extends Check implements Listener {

    private final Map<UUID, org.bukkit.Location> lastLocation = new ConcurrentHashMap<>();
    private double maxSlowedSpeed;

    public NoSlowCheck(AnticheatModule anticheat) {
        super(anticheat, "noslow", "NoSlow");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxSlowedSpeed = ConfigUtil.getBoundedDouble(plugin, path("max-slowed-speed"), 0.16, 0.05, 0.4);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (!isUsingSlowingItem(player)) {
            lastLocation.put(player.getUniqueId(), player.getLocation());
            return;
        }
        org.bukkit.Location last = lastLocation.get(player.getUniqueId());
        org.bukkit.Location now = player.getLocation();
        if (last != null && last.getWorld() != null && last.getWorld().equals(now.getWorld())) {
            double horizontalDist = Math.hypot(now.getX() - last.getX(), now.getZ() - last.getZ());
            double compensation = anticheat.getTpsMonitor().getCompensationFactor();
            double cap = maxSlowedSpeed * compensation * com.legendary.plugin.modules.anticheat.physics.VanillaPhysics.horizontalSpeedMultiplier(player);
            if (horizontalDist > cap) {
                flag(player, 1.0, String.format("speed=%.3f cap=%.3f", horizontalDist, cap));
            }
        }
        lastLocation.put(player.getUniqueId(), now);
    }

    private boolean isUsingSlowingItem(Player player) {
        return player.isBlocking() || player.isHandRaised();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastLocation.remove(event.getPlayer().getUniqueId());
    }
}
