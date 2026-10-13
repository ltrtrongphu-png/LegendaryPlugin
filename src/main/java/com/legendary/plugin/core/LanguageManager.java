package com.legendary.plugin.core;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Multi-language support for LegendaryPlugin.
 * Loads locale files (en.yml, vi.yml, zh.yml) from the plugin data folder,
 * falling back to bundled defaults. The active locale is set via
 * config.yml -> general.locale: en|vi|zh.
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class LanguageManager {

    private final LegendaryPlugin plugin;
    private final Map<String, YamlConfiguration> locales = new HashMap<>();
    private String activeLocale = "en";

    public LanguageManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        activeLocale = plugin.getConfig().getString("general.locale", "en");
        if (activeLocale == null || activeLocale.isBlank()) activeLocale = "en";

        loadLocale("en");
        loadLocale("vi");
        loadLocale("zh");

        if (!locales.containsKey(activeLocale)) {
            plugin.getLogger().warning("Unknown locale '" + activeLocale + "', falling back to 'en'.");
            activeLocale = "en";
        }
        plugin.getLogger().info("Language: " + activeLocale + " (" + get("general.prefix") + ")");
    }

    private void loadLocale(String code) {
        File file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (!file.exists()) {
            plugin.saveResource("lang/" + code + ".yml", true);
        }
        YamlConfiguration userCfg = YamlConfiguration.loadConfiguration(file);

        InputStream bundled = plugin.getResource("lang/" + code + ".yml");
        if (bundled != null) {
            YamlConfiguration bundledCfg = YamlConfiguration.loadConfiguration(
                new InputStreamReader(bundled, StandardCharsets.UTF_8));
            userCfg.setDefaults(bundledCfg);
        }
        locales.put(code, userCfg);
    }

    public String get(String path) {
        YamlConfiguration cfg = locales.get(activeLocale);
        if (cfg != null) {
            String val = cfg.getString(path);
            if (val != null) return val;
        }
        YamlConfiguration fallback = locales.get("en");
        return fallback != null ? fallback.getString(path, path) : path;
    }

    public String get(String path, String... replacements) {
        String msg = get(path);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace("%" + replacements[i] + "%", replacements[i + 1]);
        }
        return msg;
    }

    public String getLocale() {
        return activeLocale;
    }

    public java.util.Set<String> getAvailableLocales() {
        return locales.keySet();
    }
}
