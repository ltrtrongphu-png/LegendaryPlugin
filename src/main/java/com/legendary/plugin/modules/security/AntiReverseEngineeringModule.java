package com.legendary.plugin.modules.security;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import org.bukkit.Bukkit;

/**
 * Module wrapper for {@link AntiReverseEngineeringManager}. Runs at the
 * highest priority (5) so it boots before any other module and can
 * detect JVM-level tampering before anti-cheat initializes.
 */
public final class AntiReverseEngineeringModule implements Module {

    private final LegendaryPlugin plugin;
    private final AntiReverseEngineeringManager manager;
    private boolean enabled = false;

    public AntiReverseEngineeringModule(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.manager = new AntiReverseEngineeringManager(plugin);
    }

    @Override public String id() { return "anti-reverse-engineering"; }
    @Override public String displayName() { return "Anti Reverse-Engineering"; }
    @Override public int priority() { return 5; }
    @Override public boolean isEnabled() { return enabled; }

    public AntiReverseEngineeringManager getManager() { return manager; }

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
