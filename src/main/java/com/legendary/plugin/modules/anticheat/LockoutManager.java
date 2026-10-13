package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lockout system: when a player is flagged by a critical check, they are
 * immediately frozen in place and stripped of all interaction ability until
 * a staff member reviews the case. This prevents cheaters from continuing
 * to cause damage while waiting for the escalation ladder to reach kick/ban.
 *
 * A locked player:
 * - Cannot move (teleported back to their lock location every tick)
 * - Cannot break/place blocks
 * - Cannot interact with anything
 * - Cannot attack other players
 * - Cannot use commands (except staff reviewing them)
 * - Cannot open inventories
 * - CAN chat (so staff can talk to them)
 *
 * Staff with legendary.anticheat.lockout.release can release a locked player.
 * Auto-release after auto-release-seconds (0 = never auto-release).
 */
public final class LockoutManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Location> lockLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lockTimes = new ConcurrentHashMap<>();
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();
    private long autoReleaseMs;
    private org.bukkit.scheduler.BukkitTask freezeTask;

    public LockoutManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        autoReleaseMs = plugin.getConfig().getLong("anticheat.lockout.auto-release-seconds", 0) * 1000L;
    }

    public void start() {
        stop();
        freezeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::enforceFreeze, 0L, 5L);
    }

    public void stop() {
        if (freezeTask != null) { freezeTask.cancel(); freezeTask = null; }
    }

    public void lock(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        if (locked.contains(uuid)) return;
        locked.add(uuid);
        lockLocations.put(uuid, player.getLocation().clone());
        lockTimes.put(uuid, System.currentTimeMillis());
        plugin.getLogger().warning("[Lockout] " + player.getName() + " locked: " + reason);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("legendary.anticheat.alerts")) {
                staff.sendMessage(com.legendary.plugin.util.Text.of(
                    "<red>[Lockout] <white>" + player.getName() + " <gray>has been frozen: <red>" + reason
                    + " <gray>Use <white>/legendaryac release " + player.getName() + " <gray>to release."));
            }
        }
        player.sendMessage(com.legendary.plugin.util.Text.of(
            "<red>You have been frozen by anti-cheat for suspicious activity. Wait for staff review."));
    }

    public void release(Player player) {
        UUID uuid = player.getUniqueId();
        locked.remove(uuid);
        lockLocations.remove(uuid);
        lockTimes.remove(uuid);
        player.sendMessage(com.legendary.plugin.util.Text.of("<green>You have been released by staff."));
    }

    public boolean isLocked(Player player) {
        return locked.contains(player.getUniqueId());
    }

    private void enforceFreeze() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new java.util.ArrayList<>(locked)) {
            if (autoReleaseMs > 0) {
                Long lockedAt = lockTimes.get(uuid);
                if (lockedAt != null && now - lockedAt > autoReleaseMs) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) release(p);
                    else { locked.remove(uuid); lockLocations.remove(uuid); lockTimes.remove(uuid); }
                    continue;
                }
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                locked.remove(uuid);
                lockLocations.remove(uuid);
                lockTimes.remove(uuid);
                continue;
            }
            Location lockLoc = lockLocations.get(uuid);
            if (lockLoc != null && player.getLocation().distanceSquared(lockLoc) > 0.25) {
                player.teleport(lockLoc);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (isLocked(event.getPlayer())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && isLocked(p)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && isLocked(p)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (isLocked(event.getPlayer())) {
            String cmd = event.getMessage().toLowerCase();
            if (!cmd.startsWith("/legendaryac") && !cmd.startsWith("/lac")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(com.legendary.plugin.util.Text.of(
                    "<red>You are frozen and cannot use commands. Wait for staff."));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (isLocked(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(com.legendary.plugin.util.Text.of(
                "<red>You are frozen. Your message was not sent. Wait for staff."));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        locked.remove(uuid);
        lockLocations.remove(uuid);
        lockTimes.remove(uuid);
    }
}
