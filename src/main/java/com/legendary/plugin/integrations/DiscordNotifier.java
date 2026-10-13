package com.legendary.plugin.integrations;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.optimization.OptimizationEngine;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Fires a Discord webhook whenever the server's optimization level
 * changes. All network I/O runs on an async task - never on the main
 * thread. Ported from SmartOptimizer.DiscordNotifier.
 */
public final class DiscordNotifier {

    private final LegendaryPlugin plugin;
    private final String webhookUrl;

    public DiscordNotifier(LegendaryPlugin plugin, String webhookUrl) {
        this.plugin = plugin;
        this.webhookUrl = webhookUrl;
    }

    public void notifyLevelChange(OptimizationEngine.Level from, OptimizationEngine.Level to, double tps) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        String content = String.format(":warning: **%s** optimization level changed **%s -> %s** (TPS %.1f)",
            plugin.getName(), from, to, tps);
        sendWebhookAsync(content);
    }

    private void sendWebhookAsync(String content) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                String json = "{\"content\":\"" + escapeJson(content) + "\"}";
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                connection.getResponseCode(); // triggers the request; response body unused
                connection.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("Discord webhook failed: " + e.getMessage());
            }
        });
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
