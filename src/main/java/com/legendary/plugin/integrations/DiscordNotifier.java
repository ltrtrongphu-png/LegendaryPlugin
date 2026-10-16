package com.legendary.plugin.integrations;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.optimization.OptimizationEngine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Sends bounded, non-blocking Discord webhook notifications. */
public final class DiscordNotifier {

    private static final int MAX_QUEUE_SIZE = 100;
    private static final int MAX_MESSAGE_LENGTH = 1800;
    private final LegendaryPlugin plugin;
    private final URI webhookUri;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ArrayBlockingQueue<String> pendingMessages = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
    private final AtomicBoolean draining = new AtomicBoolean();

    public DiscordNotifier(LegendaryPlugin plugin, String webhookUrl) {
        this.plugin = plugin;
        this.webhookUri = validateWebhookUri(webhookUrl);
    }

    public void notifyLevelChange(OptimizationEngine.Level from, OptimizationEngine.Level to, double tps) {
        if (webhookUri == null) return;
        String content = String.format(":warning: **%s** optimization level changed **%s -> %s** (TPS %.1f)",
            plugin.getName(), from, to, tps);
        if (!pendingMessages.offer(content.substring(0, Math.min(content.length(), MAX_MESSAGE_LENGTH)))) {
            plugin.getLogger().warning("Discord notification queue is full; dropping an optimization alert.");
            return;
        }
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (!draining.compareAndSet(false, true)) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::drainQueue);
    }

    private void drainQueue() {
        try {
            String content;
            while ((content = pendingMessages.poll()) != null) sendWebhook(content);
        } finally {
            draining.set(false);
            if (!pendingMessages.isEmpty()) scheduleDrain();
        }
    }

    private void sendWebhook(String content) {
        try {
            HttpRequest request = HttpRequest.newBuilder(webhookUri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"content\":\"" + escapeJson(content)
                        + "\",\"allowed_mentions\":{\"parse\":[]}}"))
                .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 429) {
                long retrySeconds = response.headers().firstValue("Retry-After")
                    .map(this::parseRetryAfter).orElse(1L);
                if (retrySeconds > 0 && retrySeconds <= 5) {
                    Thread.sleep(retrySeconds * 1000L);
                    response = client.send(request, HttpResponse.BodyHandlers.discarding());
                }
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                plugin.getLogger().warning("Discord webhook returned HTTP " + response.statusCode() + ".");
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Discord webhook request failed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, "Discord webhook request was interrupted.", exception);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Discord webhook request configuration failed.", exception);
        }
    }

    private long parseRetryAfter(String value) {
        try {
            double seconds = Double.parseDouble(value);
            return Math.max(0L, (long) Math.ceil(seconds));
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("Discord returned an invalid Retry-After header.");
            return 1L;
        }
    }

    private static URI validateWebhookUri(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw);
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !"discord.com".equalsIgnoreCase(uri.getHost()) && !"discordapp.com".equalsIgnoreCase(uri.getHost())
                || path == null || !path.startsWith("/api/webhooks/")) {
                throw new IllegalArgumentException("Only Discord HTTPS webhook URLs are allowed.");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Discord webhook URL.", exception);
        }
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }
}
