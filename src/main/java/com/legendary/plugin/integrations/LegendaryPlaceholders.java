package com.legendary.plugin.integrations;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.optimization.OptimizationModule;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Single merged PlaceholderAPI expansion (identifier "legendary")
 * replacing the two separate expansions from AntiESPUltimate and
 * SmartOptimizer. Supported placeholders:
 *   %legendary_tps%              - current server TPS
 *   %legendary_optlevel%         - current optimization level (NORMAL/MILD/MODERATE/SEVERE)
 *   %legendary_module_<id>%      - "true"/"false" whether a module is enabled
 */
public final class LegendaryPlaceholders extends PlaceholderExpansion {

    private final LegendaryPlugin plugin;

    public LegendaryPlaceholders(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "legendary"; }
    @Override public @NotNull String getAuthor() { return "LegendaryDev"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase()) {
            case "tps" -> String.format("%.1f", plugin.getModule("optimization")
                .filter(m -> m instanceof OptimizationModule)
                .map(m -> ((OptimizationModule) m).getLastTps())
                .orElse(20.0));
            case "optlevel" -> plugin.getModule("optimization")
                .filter(m -> m instanceof OptimizationModule)
                .map(m -> ((OptimizationModule) m).getCurrentLevel().toString())
                .orElse("UNKNOWN");
            default -> handleModuleQuery(params);
        };
    }

    @Nullable
    private String handleModuleQuery(String params) {
        if (!params.startsWith("module_")) return null;
        String id = params.substring("module_".length());
        return plugin.getModule(id).map(m -> Boolean.toString(m.isEnabled())).orElse(null);
    }
}
