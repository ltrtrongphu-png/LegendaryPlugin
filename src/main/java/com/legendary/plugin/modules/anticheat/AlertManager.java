package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Broadcasts anti-cheat violation/ban alerts to staff with
 * legendary.anticheat.alerts, using MiniMessage-formatted templates from
 * config.yml.
 *
 * LOG-SPAM FIX: alerts are rate-limited per (player, check) pair. When a
 * player is actively using multiple hacks, an additional summary line is
 * sent listing all detected hack modules by name.
 */
public final class AlertManager {

    private final LegendaryPlugin plugin;
    private final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();
    private String alertPermission;
    private String alertFormat;
    private String banBroadcastFormat;
    private long cooldownMs;

    public AlertManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        alertPermission = plugin.getConfig().getString("anticheat.alert.permission", "legendary.anticheat.alerts");
        alertFormat = plugin.getConfig().getString("anticheat.alert.format",
            "<gray>[<gold>AC<gray>] <yellow><player> <gray>failed <white><check> <gray>(VL <red><vl>/<threshold>)");
        banBroadcastFormat = plugin.getConfig().getString("anticheat.alert.ban-broadcast-format",
            "<red><player> <gray>has been banned for <white><reason>");
        cooldownMs = plugin.getConfig().getLong("anticheat.alert.cooldown-ms", 3000);
        lastAlertAt.clear();
    }

    public void alertViolation(Player player, String check, double vl, double threshold) {
        alertViolation(player, check, vl, threshold, null);
    }

    public void alertViolation(Player player, String check, double vl, double threshold, List<String> activeHacks) {
        double effectiveThreshold = threshold > 0 ? threshold : plugin.getConfig().getDouble(
            "anticheat.alert.default-threshold", 10.0);
        String key = player.getUniqueId() + ":" + check;
        long now = System.currentTimeMillis();
        Long last = lastAlertAt.get(key);
        if (last != null && now - last < cooldownMs) {
            return;
        }
        lastAlertAt.put(key, now);

        var message = Text.of(alertFormat,
            Placeholder.unparsed("player", player.getName()),
            Placeholder.unparsed("check", check),
            Placeholder.unparsed("vl", String.format("%.1f", vl)),
            Placeholder.unparsed("threshold", String.format("%.1f", effectiveThreshold)));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(alertPermission)) staff.sendMessage(message);
        }
        if (activeHacks != null && !activeHacks.isEmpty()) {
            String hackList = String.join(", ", activeHacks);
            var hackMessage = Text.of("<gray>[<gold>AC<gray>] <yellow>" + player.getName()
                + " <red>is actively using: <white>" + hackList);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission(alertPermission)) staff.sendMessage(hackMessage);
            }
        }
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[AC] " + player.getName() + " failed " + check + " (VL " + String.format("%.1f", vl) + ")");
        }
    }

    public void broadcastBan(Player player, String reason) {
        var message = Text.of(banBroadcastFormat,
            Placeholder.unparsed("player", player.getName()),
            Placeholder.unparsed("reason", reason));
        Bukkit.broadcast(message);
    }
}
