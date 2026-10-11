package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.EnumSet;
import java.util.Set;

/** Flags opening a container (chest/barrel/furnace/etc.) from too far away. */
public final class ContainerReachCheck extends Check implements Listener {

    private static final Set<Material> CONTAINERS = EnumSet.of(
        Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.FURNACE,
        Material.BLAST_FURNACE, Material.SMOKER, Material.HOPPER, Material.DISPENSER,
        Material.DROPPER, Material.SHULKER_BOX, Material.ENDER_CHEST, Material.BREWING_STAND);

    private double maxReach;

    public ContainerReachCheck(AnticheatModule anticheat) {
        super(anticheat, "containerreach", "ContainerReach");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxReach = ConfigUtil.getBoundedDouble(plugin, path("max-reach"), 6.0, 3.0, 8.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        Block block = event.getClickedBlock();
        if (block == null || !CONTAINERS.contains(block.getType())) return;
        if (!player.getWorld().equals(block.getWorld())) return;
        double distance = player.getEyeLocation().distance(block.getLocation().add(0.5, 0.5, 0.5));
        if (distance > maxReach) {
            flag(player, 1.0, String.format("reach=%.2f", distance));
        }
    }
}
