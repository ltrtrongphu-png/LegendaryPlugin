package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/** Flags block break/place beyond the vanilla-plus-tolerance reach distance. */
public final class BlockReachCheck extends Check implements Listener {

    private double maxReach;

    public BlockReachCheck(AnticheatModule anticheat) {
        super(anticheat, "blockreach", "BlockReach");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxReach = ConfigUtil.getBoundedDouble(plugin, path("max-reach"), 6.0, 3.0, 8.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        checkDistance(event.getPlayer(), event.getBlock().getLocation().add(0.5, 0.5, 0.5));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        checkDistance(event.getPlayer(), event.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5));
    }

    private void checkDistance(Player player, org.bukkit.Location target) {
        if (shouldSkip(player)) return;
        if (!player.getWorld().equals(target.getWorld())) return;
        double distance = player.getEyeLocation().distance(target);
        if (distance > maxReach) {
            flag(player, 1.0, String.format("reach=%.2f", distance));
        }
    }
}
