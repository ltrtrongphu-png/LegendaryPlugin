package com.legendary.plugin.modules.antiafkbot;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.LanguageManager;
import com.legendary.plugin.core.Module;
import com.legendary.plugin.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-AFK Bot module: periodically checks all online players for inactivity
 * and kicks those who have been idle beyond the configured threshold. Supports
 * two modes:
 *   - periodic: runs a check at a fixed interval (e.g. every 1 hour)
 *   - threshold: kicks players who have been inactive for N minutes
 *
 * Players with legendary.antiafkbot.bypass are exempt. A warning is sent
 * before the kick so genuine players can move.
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class AntiAfkBotModule implements Module {

    private final LegendaryPlugin plugin;
    private final AntiAfkBotManager manager;
    private final AntiAfkBotListener listener;
    private boolean enabled = false;

    public AntiAfkBotModule(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.manager = new AntiAfkBotManager(plugin);
        this.listener = new AntiAfkBotListener(plugin, manager);
    }

    @Override public String id() { return "anti-afk-bot"; }
    @Override public String displayName() { return "Anti-AFK Bot"; }
    @Override public int priority() { return 15; }
    @Override public boolean isEnabled() { return enabled; }

    public AntiAfkBotManager getManager() { return manager; }

    @Override
    public void onEnable() {
        org.bukkit.event.HandlerList.unregisterAll(listener);
        manager.loadConfigValues();
        manager.start();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        enabled = true;
    }

    @Override
    public void onDisable() {
        manager.stop();
        org.bukkit.event.HandlerList.unregisterAll(listener);
        enabled = false;
    }
}
