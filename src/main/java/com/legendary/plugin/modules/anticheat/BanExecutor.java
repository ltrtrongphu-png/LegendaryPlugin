package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.integrations.BungeeIntegration;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BanExecutor {

    private static final Pattern DURATION = Pattern.compile("^(\\d+)([smhdw])$", Pattern.CASE_INSENSITIVE);
    private final LegendaryPlugin plugin;
    private volatile boolean requirePlugin;
    private volatile boolean dryRun;
    private volatile boolean banRequiresStaffConfirm;
    private volatile String banPluginName;
    private volatile String banCommandTemplate;
    private volatile String defaultBanDuration;
    private volatile String banReasonTemplate;
    private volatile BungeeIntegration bungeeIntegration;
    private final Map<UUID, PendingBan> pendingBans = new ConcurrentHashMap<>();

    public BanExecutor(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void setBungeeIntegration(BungeeIntegration bungeeIntegration) {
        this.bungeeIntegration = bungeeIntegration;
    }

    public void loadConfigValues() {
        requirePlugin = plugin.getConfig().getBoolean("anticheat.ban.require-plugin", false);
        dryRun = plugin.getConfig().getBoolean("anticheat.ban.dry-run", true);
        banRequiresStaffConfirm = plugin.getConfig().getBoolean("anticheat.ban.requires-staff-confirm", true);
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
        if (dryRun) {
            plugin.getLogger().warning("[AntiCheat safe-mode] Ban request for " + player.getName()
                + " was not executed because anticheat.ban.dry-run is enabled.");
            return;
        }
        if (banRequiresStaffConfirm) {
            pendingBans.put(player.getUniqueId(), new PendingBan(player, reason, duration));
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("legendary.anticheat.alerts")) {
                    staff.sendMessage(Component.text("[AntiCheat] Ban review requested for "
                        + player.getName() + ". Confirm with /legendaryac confirmban " + player.getName()));
                }
            }
            return;
        }
        executeBan(player, reason, duration);
    }

    public boolean confirmPending(String playerName) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) return false;
        PendingBan pending = pendingBans.remove(player.getUniqueId());
        if (pending == null) return false;
        executeBan(pending.player(), pending.reason(), pending.duration());
        return true;
    }

    private void executeBan(Player player, String reason, String duration) {
        Date expiresAt;
        try {
            expiresAt = Date.from(parseExpiration(duration));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid ban duration '" + duration + "'; using configured default "
                + defaultBanDuration + ".");
            try {
                expiresAt = Date.from(parseExpiration(defaultBanDuration));
            } catch (IllegalArgumentException invalidDefault) {
                plugin.getLogger().severe("Invalid anticheat.ban.default-duration: " + defaultBanDuration);
                return;
            }
        }
        plugin.getMetrics().counter("anticheat.bans").increment();
        boolean pluginPresent = Bukkit.getPluginManager().getPlugin(banPluginName) != null;
        if (pluginPresent) {
            String command = banCommandTemplate
                .replace("%player%", sanitizePlayerName(player.getName()))
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
            Date banExpiration = expiresAt;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                try {
                    player.ban(reason, banExpiration, "LegendaryPlugin");
                } catch (UnsupportedOperationException exception) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban "
                        + sanitizePlayerName(player.getName()) + " " + sanitize(duration) + " " + sanitizeReason(reason));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Ban failed for " + player.getName() + ": " + exception.getMessage());
                    return;
                }
                player.kick(Component.text("Banned: " + sanitizeReason(reason)));
                if (bungeeIntegration != null) {
                    bungeeIntegration.broadcastBan(player.getUniqueId(), player.getName(), reason, duration);
                }
            });
        }
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[;&|<>`$\\\\\\n\\r]", "").substring(0,
            Math.min(input.replaceAll("[;&|<>`$\\\\\\n\\r]", "").length(), 120));
    }

    private static String sanitizePlayerName(String input) {
        if (input == null || !input.matches("^[A-Za-z0-9_.]{1,16}$")) {
            throw new IllegalArgumentException("Invalid player name for ban command");
        }
        return input;
    }

    private static String sanitizeReason(String input) {
        return sanitize(input);
    }

    private static Instant parseExpiration(String duration) {
        Matcher matcher = DURATION.matcher(duration == null ? "" : duration.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Unsupported duration");
        long amount = Long.parseLong(matcher.group(1));
        if (amount <= 0 || amount > 36_500L) throw new IllegalArgumentException("Duration out of range");
        ChronoUnit unit = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "s" -> ChronoUnit.SECONDS;
            case "m" -> ChronoUnit.MINUTES;
            case "h" -> ChronoUnit.HOURS;
            case "d" -> ChronoUnit.DAYS;
            case "w" -> ChronoUnit.WEEKS;
            default -> throw new IllegalArgumentException("Unsupported duration");
        };
        return Instant.now().plus(amount, unit);
    }

    private record PendingBan(Player player, String reason, String duration) {}
}
