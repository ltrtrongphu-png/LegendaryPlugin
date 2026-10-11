package com.legendary.plugin.modules.security;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import org.bukkit.Bukkit;

/**
 * Module wrapper for {@link AntiDdosManager}. Runs at priority 10 so it
 * boots before verification (15) and anticheat (50), blocking flood
 * connections before they reach the join verification limbo.
 */
public final class AntiDdosModule implements Module {

    private final LegendaryPlugin plugin;
    private final AntiDdosManager manager;
    private boolean enabled = false;

    public AntiDdosModule(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.manager = new AntiDdosManager(plugin);
    }

    @Override public String id() { return "anti-ddos"; }
    @Override public String displayName() { return "Anti-DDoS Protection"; }
    @Override public int priority() { return 10; }
    @Override public boolean isEnabled() { return enabled; }

    public AntiDdosManager getManager() { return manager; }

    @Override
    public void onEnable() {
        manager.loadConfigValues();
        manager.start();
        Bukkit.getPluginManager().registerEvents(manager, plugin);
        enabled = true;
    }

    @Override
    public void onDisable() {
        manager.stop();
        enabled = false;
    }
}
