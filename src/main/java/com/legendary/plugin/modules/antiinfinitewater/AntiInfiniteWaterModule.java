package com.legendary.plugin.modules.antiinfinitewater;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import org.bukkit.Bukkit;

/**
 * Anti-Infinite-Water module — professional edition.
 *
 * Prevents ALL infinite water creation methods including:
 *   1. Manual 2x2 / 3x1 bucket pools (classic exploit)
 *   2. Dispenser-based infinite water machines (redstone + observer + dispenser)
 *   3. Automated water farms using dispensers to place water with observers
 *      triggering retraction, creating a loop that generates infinite sources
 *   4. Any water flow that would convert to a new source block via the
 *      vanilla "2 adjacent sources = new source" mechanic
 *
 * Detection layers:
 *   - BlockFromTo: cancels water flow that would create a new source
 *   - FluidLevelChange: cancels level changes that would create a source
 *   - BlockDispense: blocks dispensers from placing water (kills redstone machines)
 *   - Area scan: periodically scans loaded chunks for existing infinite water
 *     pools created by machines and removes the source blocks
 *   - Redstone machine detection: flags dispenser + observer + water patterns
 *     and alerts staff after repeated attempts
 *   - Bucket rate limit: throttles rapid manual water bucket placement
 *
 * Players with legendary.antiinfinitewater.bypass are exempt from bucket checks.
 * Dispenser water blocking applies to all players (no bypass — machines are
 * machines regardless of who placed them).
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class AntiInfiniteWaterModule implements Module {

    private final LegendaryPlugin plugin;
    private final AntiInfiniteWaterManager manager;
    private final AntiInfiniteWaterListener listener;
    private boolean enabled = false;

    public AntiInfiniteWaterModule(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.manager = new AntiInfiniteWaterManager(plugin);
        this.listener = new AntiInfiniteWaterListener(plugin, manager);
    }

    @Override public String id() { return "anti-infinite-water"; }
    @Override public String displayName() { return "Anti-Infinite Water"; }
    @Override public int priority() { return 13; }
    @Override public boolean isEnabled() { return enabled; }

    public AntiInfiniteWaterManager getManager() { return manager; }

    @Override
    public void onEnable() {
        org.bukkit.event.HandlerList.unregisterAll(listener);
        manager.loadConfigValues();
        manager.start();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        enabled = true;
    }

    @Override
    public void onDisable() {
        manager.stop();
        org.bukkit.event.HandlerList.unregisterAll(listener);
        enabled = false;
    }
}
