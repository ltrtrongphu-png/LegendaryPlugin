package com.legendary.plugin;

import com.legendary.plugin.commands.LegendaryAcCommand;
import com.legendary.plugin.commands.LegendaryCommand;
import com.legendary.plugin.core.Module;
import com.legendary.plugin.core.ModuleManager;
import com.legendary.plugin.core.JoinSpawnFallback;
import com.legendary.plugin.core.PluginMetrics;
import com.legendary.plugin.core.StartupBanner;
import com.legendary.plugin.gui.LegendaryGuiListener;
import com.legendary.plugin.integrations.LegendaryPlaceholders;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.esp.EspModule;
import com.legendary.plugin.modules.optimization.OptimizationModule;
import com.legendary.plugin.modules.replay.ReplayModule;
import com.legendary.plugin.modules.verification.VerificationModule;
import com.legendary.plugin.util.GeyserExemption;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

/**
 * LegendaryPlugin - unified Paper 1.21.4 plugin merging:
 *   - AntiESPUltimate  -> {@link EspModule}            (ESP / anti-X-ray protection)
 *   - SmartOptimizer   -> {@link OptimizationModule}    (adaptive server optimization)
 *   - BaB              -> {@link AnticheatModule}       (anti-cheat)
 *                       + {@link VerificationModule}, {@link ReplayModule} (also from BaB)
 *
 * All feature toggling goes through {@link ModuleManager}; nothing here
 * does file/network I/O directly on the main thread (see AsyncConfigLoader,
 * HistoryFileWriter, DatabaseManager, DiscordNotifier for the async paths).
 */
public final class LegendaryPlugin extends JavaPlugin {

    private ModuleManager moduleManager;
    private LegendaryGuiListener guiListener;
    private PluginMetrics metrics;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        debug = getConfig().getBoolean("debug", false);
        GeyserExemption.init();
        if (GeyserExemption.isLoaded()) {
            getLogger().info("Geyser/Floodgate detected - Bedrock players are exempt from checks marked bedrock-sensitive.");
        }

        metrics = new PluginMetrics();
        moduleManager = new ModuleManager(this);
        // Registration order = enable order. ESP/optimization have no cross-module
        // dependencies; anticheat's flag() looks up the replay module by id at call
        // time (not at enable time), so replay can safely be registered after it.
        moduleManager.register(new EspModule(this));
        moduleManager.register(new OptimizationModule(this));
        moduleManager.register(new VerificationModule(this));
        moduleManager.register(new ReplayModule(this));
        moduleManager.register(new AnticheatModule(this));
        moduleManager.enableAll();

        guiListener = new LegendaryGuiListener(this);
        Bukkit.getPluginManager().registerEvents(guiListener, this);
        Bukkit.getPluginManager().registerEvents(new JoinSpawnFallback(this), this);

        var legendaryCommand = new LegendaryCommand(this, guiListener);
        var acCommand = new LegendaryAcCommand(this);
        registerCommand("legendary", legendaryCommand, legendaryCommand);
        registerCommand("legendaryac", acCommand, acCommand);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new LegendaryPlaceholders(this).register();
            getLogger().info("Hooked into PlaceholderAPI.");
        }

        StartupBanner.print(this, moduleManager);
        getLogger().info("LegendaryPlugin enabled - modules: "
            + moduleManager.all().keySet());
        metrics.gauge("plugin.version").set(parseVersionForGauge(getPluginMeta().getVersion()));
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        // Belt-and-braces: make sure nothing registered by this plugin lingers,
        // even if a module's onDisable() threw before finishing its own cleanup.
        org.bukkit.event.HandlerList.unregisterAll(this);
        Bukkit.getScheduler().cancelTasks(this);
        getLogger().info("LegendaryPlugin disabled.");
    }

    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor, org.bukkit.command.TabCompleter completer) {
        var command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command '" + name + "' is missing from plugin.yml - skipping registration.");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(completer);
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public PluginMetrics getMetrics() {
        return metrics;
    }

    public Optional<Module> getModule(String id) {
        return moduleManager.get(id);
    }

    /** Convenience typed accessor used by checks that always expect anticheat to exist. */
    public AnticheatModule getAnticheatModule() {
        return (AnticheatModule) moduleManager.get("anticheat").orElseThrow(
            () -> new IllegalStateException("Anticheat module not registered"));
    }

    public void debug(String message) {
        if (debug) getLogger().info("[debug] " + message);
    }

    private static double parseVersionForGauge(String version) {
        if (version == null || version.isBlank()) return 0.0;
        var m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)").matcher(version);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1) + "." + m.group(2));
            } catch (NumberFormatException ignored) {}
        }
        return 0.0;
    }
}
