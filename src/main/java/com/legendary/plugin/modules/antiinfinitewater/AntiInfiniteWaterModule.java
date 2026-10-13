package com.legendary.plugin.modules.antiinfinitewater;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import org.bukkit.Bukkit;

/**
 * Optional per-player water-bucket rate limiter. It does not alter vanilla
 * fluid flow, dispenser behavior, or existing water blocks.
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
    @Override public String displayName() { return "Water Flow Limiter"; }
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
