package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.integrations.BungeeIntegration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class BanExecutor {

    private final LegendaryPlugin plugin;
    private volatile boolean requirePlugin;
    private volatile String banPluginName;
    private volatile String banCommandTemplate;
    private volatile String defaultBanDuration;
    private volatile String banReasonTemplate;
    private volatile BungeeIntegration bungeeIntegration;

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
        plugin.getMetrics().counter("anticheat.bans").increment();
        boolean pluginPresent = Bukkit.getPluginManager().getPlugin(banPluginName) != null;
        if (pluginPresent) {
            String command = banCommandTemplate
                .replace("%player%", sanitize(player.getName()))
                .replace("%duration%", sanitize(duration))
                .replace("%reason%", sanitize(reason));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                if (bungeeIntegration != null) {
                    bungeeIntegration.broadcastBan(player.getUniqueId(), player.getName(), reason, duration);
                }
            });
        } else {
            if (requirePlugin) {
                plugin.getLogger().severe("Ban requested for " + player.getName()
                    + " but ban plugin '" + banPluginName + "' is not installed and require-plugin=true. Ban skipped.");
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                try {
                    player.ban(reason, (java.util.Date) null, "LegendaryPlugin");
                } catch (Throwable e) {
                    if (e instanceof NoSuchMethodError || e instanceof AbstractMethodError) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "ban " + sanitize(player.getName()) + " " + sanitize(reason));
                    } else {
                        plugin.getLogger().warning("Ban failed for " + player.getName() + ": " + e.getMessage());
                        return;
                    }
                }
                player.kick(com.legendary.plugin.util.Text.of("<red>" + reason));
                if (bungeeIntegration != null) {
                    bungeeIntegration.broadcastBan(player.getUniqueId(), player.getName(), reason, duration);
                }
            });
        }
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[;&|<>`$\\\\\\n\\r]", "");
    }
}
