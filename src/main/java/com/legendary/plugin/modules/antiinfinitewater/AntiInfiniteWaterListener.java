package com.legendary.plugin.modules.antiinfinitewater;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

/**
 * Listener for Anti-Infinite-Water — professional edition.
 *
 * Intercepts ALL infinite water creation vectors:
 *   - Water flow that would create new source blocks (BlockFromTo)
 *   - Fluid level changes that would create sources (FluidLevelChange)
 *   - Dispensers placing water (BlockDispense) — blocks redstone machines
 *   - Manual bucket placement (PlayerBucketEmpty) — rate limits + blocks
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class AntiInfiniteWaterListener implements Listener {

    private final LegendaryPlugin plugin;
    private final AntiInfiniteWaterManager manager;

    public AntiInfiniteWaterListener(LegendaryPlugin plugin, AntiInfiniteWaterManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (!manager.isBlockFlowEnabled()) return;
        if (event.getBlock().getType() != Material.WATER) return;

        Block target = event.getToBlock();
        if (manager.wouldCreateInfiniteAt(target)) {
            event.setCancelled(true);
            plugin.getMetrics().counter("antiinfinitewater.flows.blocked").increment();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidLevelChange(FluidLevelChangeEvent event) {
        if (!manager.isBlockFlowEnabled()) return;
        Block block = event.getBlock();
        if (block.getType() != Material.WATER) return;

        if (manager.wouldCreateInfiniteAt(block)) {
            event.setCancelled(true);
            plugin.getMetrics().counter("antiinfinitewater.level-changes.blocked").increment();
        }
    }

    /**
     * Blocks dispensers from placing water. This is the key defense against
     * redstone infinite water machines that use dispensers + observers to
     * automatically create infinite water sources.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (!manager.isBlockDispenserWater()) return;

        var item = event.getItem();
        if (item == null || item.getType() != Material.WATER_BUCKET) return;

        Block dispenserBlock = event.getBlock();
        if (dispenserBlock.getType() != Material.DISPENSER) return;

        // Always block dispensers from placing water
        event.setCancelled(true);
        plugin.getMetrics().counter("antiinfinitewater.dispenser.blocked").increment();

        // Check if this dispenser is part of an infinite water machine
        if (manager.isDetectRedstoneMachines() && manager.isInfiniteWaterMachine(dispenserBlock)) {
            int warnings = manager.incrementMachineWarning(dispenserBlock);
            if (warnings >= manager.getMachineWarnThreshold()) {
                notifyMachineDetected(dispenserBlock, warnings);
                manager.clearMachineWarning(dispenserBlock);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.WATER_BUCKET) return;

        Player player = event.getPlayer();
        if (player.hasPermission("legendary.antiinfinitewater.bypass")) return;

        if (manager.isBucketRateLimitEnabled()) {
            if (!manager.checkRateLimit(player.getUniqueId())) {
                event.setCancelled(true);
                Text.send(player, "<red>[AntiInfiniteWater] <gray>You're placing water too fast. Slow down.");
                plugin.getMetrics().counter("antiinfinitewater.bucket.rate-limited").increment();
                return;
            }
        }

        Block placed = event.getBlock();
        if (manager.wouldCreateInfiniteAt(placed)) {
            event.setCancelled(true);
            Text.send(player, "<red>[AntiInfiniteWater] <gray>Infinite water creation is not allowed on this server.");
            plugin.getMetrics().counter("antiinfinitewater.bucket.blocked").increment();
            if (manager.isNotifyStaff()) {
                notifyStaff(player, placed);
            }
        }
    }

    private void notifyStaff(Player player, Block block) {
        String msg = "<red>[AntiInfiniteWater] <yellow>" + player.getName()
            + " <gray>attempted infinite water creation at "
            + block.getX() + "," + block.getY() + "," + block.getZ()
            + " in " + block.getWorld().getName();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("legendary.anticheat.alerts")) {
                Text.send(p, msg);
            }
        }
        plugin.getLogger().info("[AntiInfiniteWater] " + player.getName()
            + " attempted infinite water at " + block.getX() + "," + block.getY() + "," + block.getZ());
    }

    private void notifyMachineDetected(Block dispenserBlock, int warnings) {
        String msg = "<red>[AntiInfiniteWater] <yellow>Infinite water machine detected!"
            + " <gray>Dispenser at " + dispenserBlock.getX() + "," + dispenserBlock.getY()
            + "," + dispenserBlock.getZ() + " in " + dispenserBlock.getWorld().getName()
            + " has been blocked " + warnings + " times.";
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("legendary.anticheat.alerts")) {
                Text.send(p, msg);
            }
        }
        plugin.getLogger().warning("[AntiInfiniteWater] Infinite water machine detected at "
            + dispenserBlock.getX() + "," + dispenserBlock.getY() + "," + dispenserBlock.getZ()
            + " in " + dispenserBlock.getWorld().getName()
            + " — dispenser water placement blocked.");
        plugin.getMetrics().counter("antiinfinitewater.machines.detected").increment();
    }
}
