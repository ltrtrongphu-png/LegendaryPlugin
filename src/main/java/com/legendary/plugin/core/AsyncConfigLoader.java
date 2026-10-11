package com.legendary.plugin.core;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Loads / saves YAML files off the main thread.
 * Requirement: "no File/Config/YML I/O on the main thread". Bukkit's
 * YamlConfiguration itself is just in-memory parsing once bytes are read,
 * so the expensive part (disk I/O) is done inside runTaskAsynchronously,
 * and only the cheap field-assignment callback is scheduled back onto the
 * main thread via runTask.
 */
public final class AsyncConfigLoader {

    private AsyncConfigLoader() {}

    public static void loadAsync(LegendaryPlugin plugin, File file, Consumer<YamlConfiguration> onMainThread) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            YamlConfiguration yaml = new YamlConfiguration();
            if (file.exists()) {
                try {
                    yaml.load(file);
                } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
                    plugin.getLogger().warning("Failed to async-load " + file.getName() + ": " + e.getMessage());
                }
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> onMainThread.accept(yaml));
        });
    }

    public static void saveAsync(LegendaryPlugin plugin, YamlConfiguration yaml, File file, Runnable onDone) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to async-save " + file.getName() + ": " + e.getMessage());
            }
            if (onDone != null) {
                plugin.getServer().getScheduler().runTask(plugin, onDone);
            }
        });
    }
}
