package com.legendary.plugin.core;

import org.bukkit.configuration.file.FileConfiguration;

/** Applies additive, versioned migrations while retaining legacy config keys. */
public final class ConfigMigrator {

    public static final int CURRENT_VERSION = 1;

    private ConfigMigrator() {}

    public static int migrate(FileConfiguration config) {
        boolean hasVersion = config.contains("config-version", true);
        if (hasVersion && !config.isInt("config-version")) return -1;
        int version = hasVersion ? config.getInt("config-version") : 0;
        if (version < CURRENT_VERSION) {
            config.set("config-version", CURRENT_VERSION);
        }
        return version;
    }
}
