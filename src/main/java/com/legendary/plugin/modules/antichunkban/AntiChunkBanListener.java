package com.legendary.plugin.modules.antichunkban;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Listener for Anti-Chunk-Ban: intercepts player movement into chunks and
 * teleports them to safety if the destination chunk is dangerous or
 * quarantined.
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class AntiChunkBanListener implements Listener {

    private final LegendaryPlugin plugin;
    private final AntiChunkBanManager manager;

    public AntiChunkBanListener(LegendaryPlugin plugin, AntiChunkBanManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        if (player.hasPermission("legendary.antichunkban.bypass")) return;

        World world = event.getTo().getWorld();
        if (world == null) return;

        int chunkX = event.getTo().getBlockX() >> 4;
        int chunkZ = event.getTo().getBlockZ() >> 4;

        if (manager.isQuarantined(world, chunkX, chunkZ)) {
            event.setCancelled(true);
            Text.send(player, "<red>[AntiChunkBan] <gray>That chunk is quarantined for safety. You cannot enter it.");
            return;
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (manager.isChunkDangerous(chunk)) {
            manager.quarantine(chunk);
            event.setCancelled(true);
            Text.send(player, "<red>[AntiChunkBan] <gray>Dangerous chunk detected! Moving you to safety.");
            Location safe = manager.findSafeLocation(world, chunkX, chunkZ, player);
            Bukkit.getScheduler().runTask(plugin, () -> player.teleport(safe));
            notifyStaff(player, chunk);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        if (player.hasPermission("legendary.antichunkban.bypass")) return;

        World world = event.getTo().getWorld();
        if (world == null) return;

        int chunkX = event.getTo().getBlockX() >> 4;
        int chunkZ = event.getTo().getBlockZ() >> 4;

        if (manager.isQuarantined(world, chunkX, chunkZ)) {
            event.setCancelled(true);
            Text.send(player, "<red>[AntiChunkBan] <gray>Teleport destination is in a quarantined chunk. Teleport cancelled.");
            return;
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (manager.isChunkDangerous(chunk)) {
            manager.quarantine(chunk);
            event.setCancelled(true);
            Text.send(player, "<red>[AntiChunkBan] <gray>Teleport destination is dangerous! Redirecting to safety.");
            Location safe = manager.findSafeLocation(world, chunkX, chunkZ, player);
            Bukkit.getScheduler().runTask(plugin, () -> player.teleport(safe));
            notifyStaff(player, chunk);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("legendary.antichunkban.bypass")) return;

        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);

        if (manager.isChunkDangerous(chunk) || manager.isQuarantined(world, chunkX, chunkZ)) {
            if (manager.isChunkDangerous(chunk)) manager.quarantine(chunk);
            Location safe = manager.findSafeLocation(world, chunkX, chunkZ, player);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.teleport(safe);
                Text.send(player, "<red>[AntiChunkBan] <gray>Your login location was unsafe. You've been moved to a safe area.");
            }, 20L);
            notifyStaff(player, chunk);
        }
    }

    private void notifyStaff(Player player, Chunk chunk) {
        String msg = "<red>[AntiChunkBan] <yellow>" + player.getName()
            + " <gray>was near dangerous chunk " + chunk.getX() + "," + chunk.getZ()
            + " in " + chunk.getWorld().getName();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("legendary.anticheat.alerts")) {
                Text.send(p, msg);
            }
        }
        plugin.getLogger().warning("[AntiChunkBan] " + player.getName()
            + " encountered dangerous chunk " + chunk.getX() + "," + chunk.getZ()
            + " in " + chunk.getWorld().getName());
    }
}
