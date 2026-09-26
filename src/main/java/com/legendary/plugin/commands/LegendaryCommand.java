package com.legendary.plugin.commands;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import com.legendary.plugin.gui.LegendaryGuiListener;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import com.legendary.plugin.modules.esp.EspModule;
import com.legendary.plugin.modules.optimization.ChunkHealthReporter;
import com.legendary.plugin.modules.optimization.OptimizationEngine;
import com.legendary.plugin.modules.optimization.OptimizationModule;
import com.legendary.plugin.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** /legendary - status, module management, GUI, TPS-level simulation, top-chunks, history. */
public final class LegendaryCommand implements CommandExecutor, TabCompleter {

    private final LegendaryPlugin plugin;
    private final LegendaryGuiListener guiListener;

    public LegendaryCommand(LegendaryPlugin plugin, LegendaryGuiListener guiListener) {
        this.plugin = plugin;
        this.guiListener = guiListener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("legendary.admin")) {
            Text.send(sender, "<red>You don't have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "help" -> sendHelp(sender);
            case "status" -> sendStatus(sender);
            case "module" -> handleModule(sender, args);
            case "gui" -> handleGui(sender);
            case "simulate" -> handleSimulate(sender, args);
            case "topchunks" -> handleTopChunks(sender, args);
            case "history" -> handleHistory(sender);
            case "report" -> handleReport(sender);
            default -> Text.send(sender, "<red>Unknown subcommand. Use /legendary help.");
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        Text.send(sender, "<gradient:#38BDF8:#A855F7>LegendaryPlugin</gradient> <gray>- commands:");
        for (String line : List.of(
            "/legendary status", "/legendary module <list|enable|disable|reload> [module]",
            "/legendary gui", "/legendary simulate <mild|moderate|severe|normal>",
            "/legendary topchunks [world] [limit]", "/legendary history",
            "/legendary report")) {
            Text.send(sender, "<gray> - <white>" + line);
        }
    }

    private void sendStatus(CommandSender sender) {
        Text.send(sender, "<gray>== <white>LegendaryPlugin Status</white> ==");
        for (Module module : plugin.getModuleManager().all().values()) {
            Text.send(sender, (module.isEnabled() ? "<green>● " : "<red>● ") + "<white>"
                + module.displayName() + " <gray>(" + module.id() + ")");
        }
        plugin.getModule("optimization").filter(m -> m instanceof OptimizationModule)
            .map(m -> (OptimizationModule) m)
            .ifPresent(opt -> Text.send(sender, "<gray>TPS: <white>" + String.format("%.1f", opt.getLastTps())
                + " <gray>| Level: <yellow>" + opt.getCurrentLevel()));
        plugin.getModule("esp").filter(m -> m instanceof EspModule).map(m -> (EspModule) m)
            .ifPresent(esp -> {
                Boolean native_ = esp.getPaperAntiXraySupport().isNativeAntiXrayEnabled();
                String status = native_ == null ? "<gray>unknown (couldn't read config)"
                    : native_ ? "<green>enabled (engine-mode " + esp.getPaperAntiXraySupport().getDetectedEngineMode() + ")"
                    : "<red>disabled - see console warning";
                Text.send(sender, "<gray>Paper native Anti-Xray: " + status);
            });
    }

    private void handleModule(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Text.send(sender, "<red>Usage: /legendary module <list|enable|disable|reload> [module]");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "list" -> {
                for (Module module : plugin.getModuleManager().all().values()) {
                    Text.send(sender, "<gray>- <white>" + module.id() + " <gray>[" + (module.isEnabled() ? "<green>on" : "<red>off") + "<gray>]");
                }
            }
            case "enable" -> toggleModule(sender, args, true);
            case "disable" -> toggleModule(sender, args, false);
            case "reload" -> {
                if (args.length < 3) { Text.send(sender, "<red>Usage: /legendary module reload <module>"); return; }
                boolean ok = plugin.getModuleManager().reload(args[2]);
                Text.send(sender, ok ? "<green>Reloaded module '" + args[2] + "'." : "<red>Unknown module.");
            }
            default -> Text.send(sender, "<red>Unknown module action.");
        }
    }

    private void toggleModule(CommandSender sender, String[] args, boolean enable) {
        if (args.length < 3) { Text.send(sender, "<red>Usage: /legendary module " + (enable ? "enable" : "disable") + " <module>"); return; }
        boolean ok = enable ? plugin.getModuleManager().enable(args[2]) : plugin.getModuleManager().disable(args[2]);
        Text.send(sender, ok ? "<green>Done." : "<red>Unknown module: " + args[2]);
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) { Text.send(sender, "<red>Players only."); return; }
        guiListener.open(player);
    }

    private void handleSimulate(CommandSender sender, String[] args) {
        if (args.length < 2) { Text.send(sender, "<red>Usage: /legendary simulate <mild|moderate|severe|normal>"); return; }
        var opt = plugin.getModule("optimization").filter(m -> m instanceof OptimizationModule).map(m -> (OptimizationModule) m);
        if (opt.isEmpty()) { Text.send(sender, "<red>Optimization module is disabled."); return; }
        OptimizationEngine.Level level;
        try {
            level = OptimizationEngine.Level.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            Text.send(sender, "<red>Unknown level. Use mild, moderate, severe or normal.");
            return;
        }
        if (level == OptimizationEngine.Level.NORMAL) {
            opt.get().clearSimulation();
            Text.send(sender, "<green>Cleared simulation - back to live TPS-based levels.");
        } else {
            opt.get().simulate(level);
            Text.send(sender, "<yellow>Simulating optimization level: " + level);
        }
    }

    private void handleTopChunks(CommandSender sender, String[] args) {
        var opt = plugin.getModule("optimization").filter(m -> m instanceof OptimizationModule).map(m -> (OptimizationModule) m);
        if (opt.isEmpty()) { Text.send(sender, "<red>Optimization module is disabled."); return; }
        int limit = args.length >= 3 ? parseIntOr(args[2], 10) : 10;
        var worlds = args.length >= 2 ? List.of(Bukkit.getWorlds().stream()
                .filter(w -> w.getName().equalsIgnoreCase(args[1])).findFirst().orElse(Bukkit.getWorlds().get(0)))
            : Bukkit.getWorlds();
        Text.send(sender, "<gray>Scanning loaded chunks...");
        // Spread across ticks (20 chunks/tick) instead of scanning every loaded chunk in one
        // synchronous pass - avoids a single-tick hitch on maps with many loaded chunks.
        opt.get().getChunkHealthReporter().topChunksIncremental(worlds, limit, 20, reports -> {
            Text.send(sender, "<gray>Top " + reports.size() + " busiest chunks:");
            for (var r : reports) {
                Text.send(sender, String.format("<gray>- <white>%s [%d,%d] <gray>total=<yellow>%d <gray>(mobs=%d items=%d xp=%d stands=%d falling=%d)",
                    r.world, r.x, r.z, r.total, r.mobs, r.items, r.xpOrbs, r.armorStands, r.fallingBlocks));
            }
        });
    }

    private void handleHistory(CommandSender sender) {
        var opt = plugin.getModule("optimization").filter(m -> m instanceof OptimizationModule).map(m -> (OptimizationModule) m);
        if (opt.isEmpty()) { Text.send(sender, "<red>Optimization module is disabled."); return; }
        var history = opt.get().getMemoryHistory();
        Text.send(sender, "<gray>Last " + history.size() + " samples (in-memory):");
        int shown = 0;
        for (var point : history) {
            if (shown++ >= 10) break;
            Text.send(sender, String.format("<gray>- tps=<white>%.1f <gray>level=<yellow>%s", point.tps(), point.level()));
        }
    }

    private void handleReport(CommandSender sender) {
        Text.send(sender, "<gray>============================================");
        Text.send(sender, "<gradient:#38BDF8:#A855F7>LegendaryPlugin</gradient> <gray>Server Report");
        Text.send(sender, "<gray>============================================");

        Text.send(sender, "<gray>Modules:");
        for (Module module : plugin.getModuleManager().all().values()) {
            Text.send(sender, (module.isEnabled() ? "<green>\u25cf " : "<red>\u25cf ") + "<white>" + module.displayName() + " <gray>(" + module.id() + ")");
        }

        plugin.getModule("optimization").filter(m -> m instanceof OptimizationModule)
            .map(m -> (OptimizationModule) m)
            .ifPresent(opt -> {
                Text.send(sender, "<gray>--------------------------------------------");
                Text.send(sender, "<gray>Optimization:");
                Text.send(sender, "<gray>  TPS: <white>" + String.format("%.1f", opt.getLastTps()) + " <gray>| Level: <yellow>" + opt.getCurrentLevel());
                Text.send(sender, "<gray>  History samples (in-memory): <white>" + opt.getMemoryHistory().size());
            });

        plugin.getModule("anticheat").filter(m -> m instanceof AnticheatModule)
            .map(m -> (AnticheatModule) m)
            .ifPresent(ac -> {
                Text.send(sender, "<gray>--------------------------------------------");
                Text.send(sender, "<gray>Anti-Cheat:");
                int enabledChecks = 0;
                int totalChecks = ac.getCheckRegistry().all().size();
                for (Check check : ac.getCheckRegistry().all().values()) {
                    if (check.isEnabled()) enabledChecks++;
                }
                Text.send(sender, "<gray>  Checks: <white>" + enabledChecks + "/" + totalChecks + " <gray>active");
                Text.send(sender, "<gray>  Database: <white>" + (ac.getDatabaseManager().isConnected() ? "<green>connected" : "<red>disconnected"));
                int watching = ac.getWatchManager().getWatchCount();
                if (watching > 0) Text.send(sender, "<gray>  Watching: <white>" + watching + " <gray>player(s)");
                int suspicious = 0, verified = 0;
                for (var entry : ac.getTrustManager().getAllTrustScores().entrySet()) {
                    if (entry.getValue() < 20) suspicious++;
                    else if (entry.getValue() >= 80) verified++;
                }
                Text.send(sender, "<gray>  Trust: <green>" + verified + " verified<gray>, <red>" + suspicious + " high-risk<gray>, <white>" + ac.getTrustManager().getAllTrustScores().size() + " total");
            });

        plugin.getModule("esp").filter(m -> m instanceof EspModule).map(m -> (EspModule) m)
            .ifPresent(esp -> {
                Text.send(sender, "<gray>--------------------------------------------");
                Text.send(sender, "<gray>ESP / Anti-X-ray:");
                Boolean native_ = esp.getPaperAntiXraySupport().isNativeAntiXrayEnabled();
                String status = native_ == null ? "<gray>unknown"
                    : native_ ? "<green>enabled (engine-mode " + esp.getPaperAntiXraySupport().getDetectedEngineMode() + ")"
                    : "<red>disabled";
                Text.send(sender, "<gray>  Paper native Anti-Xray: " + status);
            });

        Text.send(sender, "<gray>--------------------------------------------");
        Text.send(sender, "<gray>Players online: <white>" + Bukkit.getOnlinePlayers().size());
        Text.send(sender, "<gray>Worlds: <white>" + Bukkit.getWorlds().size());
        Text.send(sender, "<gray>============================================");
    }

    private int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return filter(args[0], List.of("help", "status", "module", "gui", "simulate", "topchunks", "history", "report"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("module")) {
            return filter(args[1], List.of("list", "enable", "disable", "reload"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("module")) {
            return filter(args[2], plugin.getModuleManager().all().keySet().stream().toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("simulate")) {
            return filter(args[1], List.of("mild", "moderate", "severe", "normal"));
        }
        return List.of();
    }

    private List<String> filter(String prefix, List<String> options) {
        return options.stream().filter(o -> o.toLowerCase().startsWith(prefix.toLowerCase())).collect(Collectors.toList());
    }
}
