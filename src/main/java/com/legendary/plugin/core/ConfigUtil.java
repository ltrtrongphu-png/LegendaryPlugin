package com.legendary.plugin.core;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Small helpers for reading bounded numeric config values safely, logging
 * (once) and clamping instead of throwing when an admin puts a bad value
 * in config.yml. Ported/unified from the three legacy ConfigUtil classes.
 */
public final class ConfigUtil {

    private ConfigUtil() {}

    public static int getBoundedInt(JavaPlugin plugin, String path, int def, int min, int max) {
        FileConfiguration cfg = plugin.getConfig();
        int value = cfg.getInt(path, def);
        if (value < min || value > max) {
            plugin.getLogger().warning("[config] '" + path + "' = " + value + " out of range [" + min + "," + max + "], clamping.");
            value = Math.max(min, Math.min(max, value));
        }
        return value;
    }

    public static double getBoundedDouble(JavaPlugin plugin, String path, double def, double min, double max) {
        FileConfiguration cfg = plugin.getConfig();
        double value = cfg.getDouble(path, def);
        if (value < min || value > max) {
            plugin.getLogger().warning("[config] '" + path + "' = " + value + " out of range [" + min + "," + max + "], clamping.");
            value = Math.max(min, Math.min(max, value));
        }
        return value;
    }

    public static long getBoundedLong(JavaPlugin plugin, String path, long def, long min, long max) {
        FileConfiguration cfg = plugin.getConfig();
        long value = cfg.getLong(path, def);
        if (value < min || value > max) {
            plugin.getLogger().warning("[config] '" + path + "' = " + value + " out of range [" + min + "," + max + "], clamping.");
            value = Math.max(min, Math.min(max, value));
        }
        return value;
    }
}
