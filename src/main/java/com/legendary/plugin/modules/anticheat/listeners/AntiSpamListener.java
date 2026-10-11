package com.legendary.plugin.modules.anticheat.listeners;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limits chat messages per player within a sliding window, kicking
 * (and optionally banning as a fallback) repeat spammers. Note: chat
 * events already fire asynchronously in Bukkit, so no extra async
 * scheduling is needed here - only the resulting kick/ban is scheduled
 * back onto the main thread. Ported from BaB.AntiSpamListener.
 */
public final class AntiSpamListener implements Listener {

    private final LegendaryPlugin plugin;
    private final AnticheatModule anticheat;
    private final Map<UUID, Deque<Long>> timestamps = new ConcurrentHashMap<>();
    private long windowMs = 8000;
    private int maxMessages = 6;
    private String escalation = "kick";
    private String fallbackBanReason = "Spam";
    private String fallbackBanDuration = "1d";

    public AntiSpamListener(LegendaryPlugin plugin, AnticheatModule anticheat) {
        this.plugin = plugin;
        this.anticheat = anticheat;
    }

    public void loadConfigValues() {
        windowMs = plugin.getConfig().getLong("anticheat.checks.antispam.window-ms", 8000);
        maxMessages = plugin.getConfig().getInt("anticheat.checks.antispam.max-messages", 6);
        escalation = plugin.getConfig().getString("anticheat.checks.antispam.escalation", "kick");
        fallbackBanReason = plugin.getConfig().getString("anticheat.checks.antispam.fallback-ban-reason", "Spam");
        fallbackBanDuration = plugin.getConfig().getString("anticheat.checks.antispam.fallback-ban-duration", "1d");
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (anticheat.getBypassGuard().shouldBypass(player, "antispam")) return;
        long now = System.currentTimeMillis();
        Deque<Long> deque = timestamps.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        boolean overLimit;
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) deque.pollFirst();
            overLimit = deque.size() > maxMessages;
        }
        if (overLimit) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if ("ban".equalsIgnoreCase(escalation)) {
                    anticheat.getBanExecutor().banDirect(player, fallbackBanReason, fallbackBanDuration);
                } else {
                    player.kick(com.legendary.plugin.util.Text.of("<red>Kicked for chat spam."));
                }
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        timestamps.remove(event.getPlayer().getUniqueId());
    }
}
