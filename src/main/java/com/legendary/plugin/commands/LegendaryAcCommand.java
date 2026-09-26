package com.legendary.plugin.commands;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import com.legendary.plugin.modules.anticheat.HackProfileManager;
import com.legendary.plugin.modules.anticheat.ThreatActionManager;
import com.legendary.plugin.modules.replay.ReplayClip;
import com.legendary.plugin.modules.replay.ReplayModule;
import com.legendary.plugin.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** /legendaryac - anti-cheat administration: violation levels, resets, replay review, hack profiles. */
public final class LegendaryAcCommand implements CommandExecutor, TabCompleter {

    private final LegendaryPlugin plugin;

    public LegendaryAcCommand(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private AnticheatModule ac() {
        return plugin.getModule("anticheat")
            .filter(m -> m instanceof AnticheatModule)
            .map(m -> (AnticheatModule) m)
            .orElse(null);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("legendary.anticheat.admin")) {
            Text.send(sender, "<red>You don't have permission to use this command.");
            return true;
        }
        AnticheatModule anticheat = ac();
        if (anticheat == null) { Text.send(sender, "<red>Anti-cheat module is disabled."); return true; }
        if (args.length == 0) {
            Text.send(sender, "<gray>Usage: /legendaryac <vl|exempt|reset|checkall|replay|history|watch|trust|profile|threats> ...");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "vl" -> handleVl(sender, anticheat, args);
            case "reset" -> handleReset(sender, anticheat, args);
            case "checkall" -> handleCheckAll(sender, anticheat, args);
            case "replay" -> handleReplay(sender, args);
            case "history" -> handleHistory(sender, anticheat, args);
            case "watch" -> handleWatch(sender, anticheat, args);
            case "trust" -> handleTrust(sender, anticheat, args);
            case "profile" -> handleProfile(sender, anticheat, args);
            case "threats" -> handleThreats(sender, anticheat);
            case "bypass" -> handleBypass(sender, anticheat, args);
            case "bypass-audit" -> handleBypassAudit(sender, anticheat, args);
            default -> Text.send(sender, "<red>Unknown subcommand.");
        }
        return true;
    }

    private void handleVl(CommandSender sender, AnticheatModule anticheat, String[] args) {
        if (args.length < 2) { Text.send(sender, "<red>Usage: /legendaryac vl <player> [check]"); return; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!(target instanceof Player online)) { Text.send(sender, "<red>Player must be online."); return; }
        if (args.length >= 3) {
            double vl = anticheat.getViolationManager().getVl(online, args[2].toLowerCase());
            Text.send(sender, "<gray>" + args[1] + " " + args[2] + " VL: <white>" + String.format("%.1f", vl));
        } else {
            for (Check check : anticheat.getCheckRegistry().all().values()) {
                double vl = anticheat.getViolationManager().getVl(online, check.getId());
                if (vl > 0) Text.send(sender, "<gray>- " + check.getDisplayName() + ": <white>" + String.format("%.1f", vl));
            }
        }
    }

    private void handleReset(CommandSender sender, AnticheatModule anticheat, String[] args) {
        if (args.length < 2) { Text.send(sender, "<red>Usage: /legendaryac reset <player> [check]"); return; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!(target instanceof Player online)) { Text.send(sender, "<red>Player must be online."); return; }
        if (args.length >= 3) {
            anticheat.getViolationManager().resetCheck(online, args[2].toLowerCase());
        } else {
            anticheat.getViolationManager().resetAll(online);
        }
        Text.send(sender, "<green>Reset violations for " + args[1] + ".");
    }

    private void handleCheckAll(CommandSender sender, AnticheatModule anticheat, String[] args) {
        if (args.length >= 2) {
            handleVl(sender, anticheat, args);
            return;
        }
        Text.send(sender, "<gray>Check MODULES (on/off status - not a per-player scan; "
            + "use <white>/legendaryac checkall <player><gray> or <white>/legendaryac vl <player><gray> for that):");
        Text.send(sender, "<gray>Registered checks (" + anticheat.getCheckRegistry().all().size() + "):");
        for (Check check : anticheat.getCheckRegistry().all().values()) {
            Text.send(sender, (check.isEnabled() ? "<green>● " : "<red>● ") + "<white>" + check.getDisplayName());
        }
    }

    private void handleWatch(CommandSender sender, AnticheatModule anticheat, String[] args) {
        if (!(sender instanceof Player staff)) { Text.send(sender, "<red>Players only."); return; }
        if (args.length < 2) { Text.send(sender, "<red>Usage: /legendaryac watch <player>"); return; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { Text.send(sender, "<red>Player must be online."); return; }
        boolean nowWatching = anticheat.getWatchManager().toggleWatch(staff, target);
        Text.send(sender, nowWatching
            ? "<green>Now watching <white>" + target.getName() + "<green> - you'll get detailed alerts for every flag."
            : "<yellow>Stopped watching <white>" + target.getName() + "<yellow>.");
    }

    private void handleHistory(CommandSender sender, AnticheatModule anticheat, String[] args) {
        if (args.length < 2) { Text.send(sender, "<red>Usage: /legendaryac history <player> [limit]"); return; }
        if (!anticheat.getDatabaseManager().isConnected()) {
            Text.send(sender, "<red>Database isn't connected yet - try again in a moment.");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        int limit = args.length >= 3 ? parseIntOr(args[2], 15) : 15;
        Text.send(sender, "<gray>Fetching violation history for <white>" + args[1] + "<gray>...");
        anticheat.getDatabaseManager().getRecentViolationsAsync(target.getUniqueId().toString(), limit, rows -> {
            if (rows.isEmpty()) {
                Text.send(sender, "<gray>No recorded history for " + args[1] + ".");
                return;
            }
            Text.send(sender, "<gray>Last " + rows.size() + " violation(s) for <white>" + args[1] + "<gray>:");
            for (var row : rows) {
                Text.send(sender, String.format("<gray>- <white>%s <gray>weight=%.1f vl=%.1f <dark_gray>(%s)",
                    row.checkName(), row.weight(), row.vlAfter(),
                    java.time.Instant.ofEpochMilli(row.createdAt()).toString()));
            }
        });
    }

    private void handleTrust(CommandSender sender, AnticheatModule anticheat, String[] args) {
        if (args.length < 2) {
            Text.send(sender, "<red>Usage: /legendaryac trust <player> [set <value>|reset]");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { Text.send(sender, "<red>Player must be online."); return; }
        var trust = anticheat.getTrustManager();
        if (args.length >= 3 && args[2].equalsIgnoreCase("set") && args.length >= 4) {
            try {
                double value = Double.parseDouble(args[3]);
                trust.setTrust(target, value);
                Text.send(sender, "<green>Set trust for <white>" + target.getName() + " <green>to <white>" + String.format("%.1f", value));
            } catch (NumberFormatException e) {
                Text.send(sender, "<red>Value must be a number.");
            }
            return;
        }
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            trust.resetTrust(target);
            Text.send(sender, "<green>Reset trust for <white>" + target.getName() + " <green>to default.");
            return;
        }
        double score = trust.getTrust(target);
        String label = trust.getTrustLabel(target);
        long days = trust.getDaysSinceFirstJoin(target);
        String labelColor = switch (label) {
            case "Verified" -> "<green>";
            case "Trusted" -> "<dark_green>";
            case "Neutral" -> "<white>";
            case "Suspicious" -> "<yellow>";
            default -> "<red>";
        };
        Text.send(sender, "<gray>== <white>" + target.getName() + " <gray>==");
        Text.send(sender, "<gray>Trust: " + labelColor + String.format("%.1f", score) + " <gray>(" + labelColor + label + "<gray>)");
        Text.send(sender, "<gray>Days on server: <white>" + days);
        double mult = trust.getWeightMultiplier(target);
        Text.send(sender, "<gray>Detection sensitivity: <white>" + String.format("%.0f%%", mult * 100));
    }

    private void handleProfile(CommandSender sender, AnticheatModule anticheat, String[] args) {
        if (args.length < 2) { Text.send(sender, "<red>Usage: /legendaryac profile <player>"); return; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { Text.send(sender, "<red>Player must be online."); return; }
        HackProfileManager hpm = anticheat.getHackProfileManager();
        List<String> activeHacks = hpm.getActiveHacks(target);
        double threat = hpm.getThreatScore(target);
        String label = hpm.getThreatLabel(target);
        String color = hpm.getThreatColor(threat);
        Text.send(sender, "<gray>== <white>" + target.getName() + " <gray>Hack Profile ==");
        Text.send(sender, "<gray>Threat: " + color + String.format("%.1f", threat) + " <gray>(" + color + label + "<gray>)");
        if (activeHacks.isEmpty()) {
            Text.send(sender, "<green>No active hacks detected.");
        } else {
            Text.send(sender, "<red>Active hacks (" + activeHacks.size() + "):");
            for (String hack : activeHacks) {
                Text.send(sender, "<red>  \u2716 <white>" + hack);
            }
        }
    }

    private void handleThreats(CommandSender sender, AnticheatModule anticheat) {
        ThreatActionManager tam = anticheat.getThreatActionManager();
        List<ThreatActionManager.ThreatEntry> entries = tam.getThreatList();
        if (entries.isEmpty()) {
            Text.send(sender, "<green>No active threats detected. All clear.");
            return;
        }
        Text.send(sender, "<gray>============================================");
        Text.send(sender, "<red>Live Threat Dashboard <gray>(" + entries.size() + " active)");
        Text.send(sender, "<gray>============================================");
        for (ThreatActionManager.ThreatEntry entry : entries) {
            String color = anticheat.getHackProfileManager().getThreatColor(entry.score());
            String hacks = entry.hacks().isEmpty() ? "unknown" : String.join(", ", entry.hacks());
            Text.send(sender, color + String.format("%5.1f", entry.score()) + " <gray>[" + color + entry.label() + "<gray>] <white>"
                + entry.playerName() + " <gray>\u2192 <yellow>" + hacks);
        }
        Text.send(sender, "<gray>============================================");
    }

    private void handleBypass(CommandSender sender, AnticheatModule anticheat, String[] args) {
        if (args.length < 3) {
            Text.send(sender, "<red>Usage: /legendaryac bypass <player> <seconds|revoke>");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { Text.send(sender, "<red>Player must be online."); return; }
        var guard = anticheat.getBypassGuard();
        if (args[2].equalsIgnoreCase("revoke")) {
            guard.revokeTemporaryBypass(target.getUniqueId());
            Text.send(sender, "<yellow>Revoked temporary bypass for <white>" + target.getName() + "<yellow>.");
            return;
        }
        try {
            long seconds = Long.parseLong(args[2]);
            guard.grantTemporaryBypass(target.getUniqueId(), seconds);
            Text.send(sender, "<green>Granted temporary bypass to <white>" + target.getName()
                + " <green>for <white>" + seconds + "s<green>. Critical checks still active.");
        } catch (NumberFormatException e) {
            Text.send(sender, "<red>Seconds must be a number, or 'revoke'.");
        }
    }

    private void handleBypassAudit(CommandSender sender, AnticheatModule anticheat, String[] args) {
        var guard = anticheat.getBypassGuard();
        if (args.length < 2) {
            Set<String> critical = guard.getCriticalChecks();
            Text.send(sender, "<gray>== Anti-Bypass Status ==");
            Text.send(sender, "<gray>Critical checks (never bypassed): <white>"
                + (critical.isEmpty() ? "(none)" : String.join(", ", critical)));
            Text.send(sender, "<gray>Usage: /legendaryac bypass-audit <player>");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { Text.send(sender, "<red>Player must be online."); return; }
        var log = guard.getAuditLog(target.getUniqueId());
        if (log.isEmpty()) {
            Text.send(sender, "<green>No bypass usage recorded for <white>" + target.getName() + "<green>.");
            return;
        }
        Text.send(sender, "<gray>== Bypass Audit: <white>" + target.getName() + " <gray>==");
        for (var entry : log.entrySet()) {
            long ago = (System.currentTimeMillis() - entry.getValue()) / 1000;
            Text.send(sender, "<gray>- <white>" + entry.getKey() + " <gray>" + ago + "s ago");
        }
    }

    private int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private void handleReplay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player staff)) { Text.send(sender, "<red>Players only."); return; }
        var replay = plugin.getModule("replay").filter(m -> m instanceof ReplayModule).map(m -> (ReplayModule) m);
        if (replay.isEmpty()) { Text.send(sender, "<red>Replay module is disabled."); return; }
        if (args.length < 2) { Text.send(sender, "<red>Usage: /legendaryac replay <list|watch|stop> [player] [index]"); return; }

        switch (args[1].toLowerCase()) {
            case "stop" -> replay.get().getPlaybackManager().stop(staff);
            case "list" -> {
                if (args.length < 3) { Text.send(sender, "<red>Usage: /legendaryac replay list <player>"); return; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                List<ReplayClip> clips = replay.get().getClips(target.getUniqueId());
                Text.send(sender, "<gray>" + clips.size() + " saved clip(s):");
                for (int i = 0; i < clips.size(); i++) {
                    Text.send(sender, "<gray>[" + i + "] <white>" + clips.get(i).triggeringCheck);
                }
            }
            case "watch" -> {
                if (args.length < 4) { Text.send(sender, "<red>Usage: /legendaryac replay watch <player> <index>"); return; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                List<ReplayClip> clips = replay.get().getClips(target.getUniqueId());
                try {
                    int idx = Integer.parseInt(args[3]);
                    if (idx < 0 || idx >= clips.size()) { Text.send(sender, "<red>Invalid clip index."); return; }
                    replay.get().getPlaybackManager().watch(staff, clips.get(idx));
                    Text.send(sender, "<green>Watching clip " + idx + ". Use /legendaryac replay stop to return.");
                } catch (NumberFormatException e) {
                    Text.send(sender, "<red>Index must be a number.");
                }
            }
            default -> Text.send(sender, "<red>Unknown replay action.");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return filter(args[0], List.of("vl", "exempt", "reset", "checkall", "replay", "history", "watch", "trust", "profile", "threats", "bypass", "bypass-audit"));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("vl") || args[0].equalsIgnoreCase("reset")
                || args[0].equalsIgnoreCase("history") || args[0].equalsIgnoreCase("watch")
                || args[0].equalsIgnoreCase("checkall") || args[0].equalsIgnoreCase("trust")
                || args[0].equalsIgnoreCase("profile")
                || args[0].equalsIgnoreCase("bypass")
                || args[0].equalsIgnoreCase("bypass-audit"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("trust")) {
            return filter(args[2], List.of("set", "reset"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("replay")) {
            return filter(args[1], List.of("list", "watch", "stop"));
        }
        return List.of();
    }

    private List<String> filter(String prefix, List<String> options) {
        return options.stream().filter(o -> o.toLowerCase().startsWith(prefix.toLowerCase())).collect(Collectors.toList());
    }
}
