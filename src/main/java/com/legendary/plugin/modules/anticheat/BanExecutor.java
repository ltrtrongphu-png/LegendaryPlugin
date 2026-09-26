package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.integrations.BungeeIntegration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Executes bans either via a dedicated ban-management plugin's command
 * (recommended - supports proper durations/history) or, as a fallback,
 * a plain Bukkit kick-ban. Ported from BaB.BanExecutor.
 */
public final class BanExecutor {

    private final LegendaryPlugin plugin;
    private boolean requirePlugin;
    private String banPluginName;
    private String banCommandTemplate;
    private String defaultBanDuration;
    private String banReasonTemplate;
    private BungeeIntegration bungeeIntegration; // optional, wired by AnticheatModule if enabled

    public BanExecutor(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void setBungeeIntegration(BungeeIntegration bungeeIntegration) {
        this.bungeeIntegration = bungeeIntegration;
    }

    public void loadConfigValues() {
        requirePlugin = plugin.getConfig().getBoolean("anticheat.ban.require-plugin", false);
        banPluginName = plugin.getConfig().getString("anticheat.ban.plugin-name", "LightBans");
        banCommandTemplate = plugin.getConfig().getString("anticheat.ban.command-template", "ban %player% %duration% %reason%");
        defaultBanDuration = plugin.getConfig().getString("anticheat.ban.default-duration", "30d");
        banReasonTemplate = plugin.getConfig().getString("anticheat.ban.reason-template", "AutoBan: %check% (VL %vl%)");
    }

    public void ban(Player player, String checkName, double vl) {
        String reason = banReasonTemplate.replace("%check%", checkName).replace("%vl%", String.format("%.1f", vl));
        banDirect(player, reason, defaultBanDuration);
    }

    public void banDirect(Player player, String reason, String duration) {
        if (bungeeIntegration != null) {
            bungeeIntegration.broadcastBan(player.getUniqueId(), player.getName(), reason, duration);
        }
        boolean pluginPresent = Bukkit.getPluginManager().getPlugin(banPluginName) != null;
        if (pluginPresent) {
            String command = banCommandTemplate
                .replace("%player%", sanitize(player.getName()))
                .replace("%duration%", sanitize(duration))
                .replace("%reason%", sanitize(reason));
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        } else {
            if (requirePlugin) {
                plugin.getLogger().severe("Ban requested for " + player.getName()
                    + " but ban plugin '" + banPluginName + "' is not installed and require-plugin=true. Ban skipped.");
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    player.ban(reason, (java.util.Date) null, "LegendaryPlugin");
                } catch (NoSuchMethodError e) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "ban " + sanitize(player.getName()) + " " + sanitize(reason));
                }
                player.kick(com.legendary.plugin.util.Text.of("<red>" + reason));
            });
        }
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[;&|<>`$\\\\\\n\\r]", "");
    }
}
