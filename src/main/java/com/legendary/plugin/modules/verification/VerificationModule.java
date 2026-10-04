package com.legendary.plugin.modules.verification;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;

/** Aggregates join-verification (anti-bot limbo) + IP rate limiting into one {@link Module}. */
public final class VerificationModule implements Module {

    private final LegendaryPlugin plugin;
    private final VerificationManager verificationManager;
    private final VerificationListener verificationListener;
    private boolean enabled = false;

    public VerificationModule(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.verificationManager = new VerificationManager(plugin);
        this.verificationListener = new VerificationListener(plugin, verificationManager);
    }

    @Override public String id() { return "verification"; }
    @Override public String displayName() { return "Join Verification (Anti-Bot)"; }
    @Override public int priority() { return 15; }
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void onEnable() {
        verificationManager.loadConfigValues();
        verificationManager.start();
        verificationListener.loadConfigValues();
        Bukkit.getPluginManager().registerEvents(verificationListener, plugin);
        enabled = true;
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(verificationListener);
        verificationManager.stop();
        enabled = false;
    }
}
