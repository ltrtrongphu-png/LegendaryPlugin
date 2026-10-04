package com.legendary.plugin.modules.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class CheckRegistry {

    private final Plugin plugin;
    private final AnticheatModule anticheat;
    private final Map<String, Check> checks = new LinkedHashMap<>();

    public CheckRegistry(Plugin plugin, AnticheatModule anticheat) {
        this.plugin = plugin;
        this.anticheat = anticheat;
    }

    public void register(Check check) {
        checks.put(check.getId().toLowerCase(), check);
    }

    public void enableAllConfigured() {
        CheckProfile profile = anticheat.getCheckProfile();
        for (Check check : checks.values()) {
            check.loadConfigValues();
            Boolean presetOverride = profile != null ? profile.shouldEnable(check.getId().toLowerCase()) : null;
            boolean shouldEnable = presetOverride != null ? presetOverride : check.isEnabled();
            check.setEnabled(shouldEnable);
            if (shouldEnable) {
                HandlerList.unregisterAll(check);
                Bukkit.getPluginManager().registerEvents(check, plugin);
            }
        }
    }

    public void disableAll() {
        for (Check check : checks.values()) {
            check.setEnabled(false);
            HandlerList.unregisterAll(check);
        }
    }

    public boolean setEnabled(String id, boolean value) {
        Check check = checks.get(id.toLowerCase());
        if (check == null) return false;
        HandlerList.unregisterAll(check);
        check.setEnabled(value);
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
        return Collections.unmodifiableMap(checks);
    }
}
