package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomly cancels a fraction of hopper item-transfer events under load
 * to relieve redstone/hopper-clock lag. Ported from
 * SmartOptimizer.HopperThrottleListener.
 */
public final class HopperThrottleListener implements Listener {

    private final LegendaryPlugin plugin;
    private volatile double throttleChance = 0.0;
    private volatile boolean enabled = true;

    public HopperThrottleListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void updateSettings(OptimizationEngine.Settings settings) {
        this.throttleChance = settings.hopperThrottleChance;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent event) {
        if (!enabled || throttleChance <= 0) return;
        if (ThreadLocalRandom.current().nextDouble() < throttleChance) {
            event.setCancelled(true);
            if (event.getSource().getType() == org.bukkit.event.inventory.InventoryType.HOPPER
                && event.getDestination().getType() == org.bukkit.event.inventory.InventoryType.HOPPER) {
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try { event.getSource().getLocation().getBlock().getState().update(); } catch (Throwable ignored) {}
                }, 2L);
            }
        }
    }
}
