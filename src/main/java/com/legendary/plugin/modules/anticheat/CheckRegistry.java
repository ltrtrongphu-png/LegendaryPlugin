package com.legendary.plugin.modules.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registers/enables/disables individual {@link Check} listeners, keyed by
 * their id (e.g. "killaura"), so each can be toggled independently via
 * /legendaryac or config.yml without touching the others.
 */
public final class CheckRegistry {

    private final Plugin plugin;
    private final Map<String, Check> checks = new LinkedHashMap<>();

    public CheckRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public void register(Check check) {
        checks.put(check.getId(), check);
    }

    public void enableAllConfigured() {
        for (Check check : checks.values()) {
            check.loadConfigValues();
            if (check.isEnabled()) {
                Bukkit.getPluginManager().registerEvents(check, plugin);
            }
        }
    }

    public void disableAll() {
        for (Check check : checks.values()) {
            HandlerList.unregisterAll(check);
        }
    }

    public boolean setEnabled(String id, boolean value) {
        Check check = checks.get(id.toLowerCase());
        if (check == null) return false;
        HandlerList.unregisterAll(check);
        if (value) {
            check.loadConfigValues();
            Bukkit.getPluginManager().registerEvents(check, plugin);
        }
        return true;
    }

    public Optional<Check> get(String id) {
        return Optional.ofNullable(checks.get(id.toLowerCase()));
    }

    public Map<String, Check> all() {
        return checks;
    }
}
