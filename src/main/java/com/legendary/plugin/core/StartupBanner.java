package com.legendary.plugin.core;

import com.legendary.plugin.LegendaryPlugin;

import java.util.Map;

/**
 * Premium startup banner with ASCII art and a live module status table.
 * Replaces the plain 3-line log block with a branded, informative boot
 * summary that gives operators an immediate view of what loaded and
 * what didn't.
 */
public final class StartupBanner {

    private static final String[] BANNER_ART = {
        "  _                              _ _     _",
        " | |    __ _ _ __ __ _ _ __ _   _(_) | __| |",
        " | |   / _` | '__/ _` | '__| | | | | |/ _` |",
        " | |__| (_| | | | (_| | |  | |_| | | | (_| |",
        " |_____\\__,_|_|  \\__,_|_|   \\__,_|_|_|\\__,_|"
    };

    private StartupBanner() {}

    public static void print(LegendaryPlugin plugin, ModuleManager mm) {
        String[] border = box("LegendaryPlugin Premium v" + plugin.getPluginMeta().getVersion());
        for (String line : border) plugin.getLogger().info(line);
        plugin.getLogger().info("");
        for (String line : BANNER_ART) plugin.getLogger().info(line);
        plugin.getLogger().info("");
        plugin.getLogger().info("  Paper 1.21.4 | Anti-Cheat | Anti-ESP | Optimization | Verification | Replay");
        plugin.getLogger().info("");

        plugin.getLogger().info("  Module Status:");
        plugin.getLogger().info("  " + repeat('-', 52));
        for (Map.Entry<String, Module> entry : mm.all().entrySet()) {
            String id = entry.getKey();
            Module m = entry.getValue();
            boolean enabled = m.isEnabled();
            String status = enabled ? "ON " : "OFF";
            String color = enabled ? "OK" : "--";
            plugin.getLogger().info(String.format("    [%s] %-14s %-20s (priority %d)",
                status, id, m.displayName(), m.priority()));
        }
        plugin.getLogger().info("  " + repeat('-', 52));
        plugin.getLogger().info("");
    }

    private static String[] box(String text) {
        String top = "  " + repeat('=', text.length() + 4);
        String mid = "  |  " + text + "  |";
        String bot = "  " + repeat('=', text.length() + 4);
        return new String[] { top, mid, bot };
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(Math.max(0, n));
    }
}
