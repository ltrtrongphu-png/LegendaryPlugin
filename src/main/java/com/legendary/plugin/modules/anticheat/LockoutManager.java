package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;
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
    private final Map<UUID, Boolean> previousInvulnerability = new ConcurrentHashMap<>();
    private NamespacedKey lockedKey;
    private NamespacedKey worldKey;
    private NamespacedKey xKey;
    private NamespacedKey yKey;
    private NamespacedKey zKey;
    private NamespacedKey lockedAtKey;
    private NamespacedKey wasInvulnerableKey;
    private long autoReleaseMs;
    private boolean safeMode;
    private org.bukkit.scheduler.BukkitTask freezeTask;

    public LockoutManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        autoReleaseMs = Math.max(1L, plugin.getConfig().getLong("anticheat.lockout.auto-release-seconds", 120)) * 1000L;
        safeMode = plugin.getConfig().getBoolean("anticheat.safe-mode", true);
        lockedKey = new NamespacedKey(plugin, "anticheat_lockout");
        worldKey = new NamespacedKey(plugin, "anticheat_lockout_world");
        xKey = new NamespacedKey(plugin, "anticheat_lockout_x");
        yKey = new NamespacedKey(plugin, "anticheat_lockout_y");
        zKey = new NamespacedKey(plugin, "anticheat_lockout_z");
        lockedAtKey = new NamespacedKey(plugin, "anticheat_lockout_at");
        wasInvulnerableKey = new NamespacedKey(plugin, "anticheat_lockout_invulnerable");
        if (safeMode) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (isLocked(player)) release(player);
            }
        }
    }

    public void start() {
        stop();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (locked.contains(player.getUniqueId())) player.setInvulnerable(true);
        }
        freezeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::enforceFreeze, 0L, 5L);
    }

    public void stop() {
        if (freezeTask != null) { freezeTask.cancel(); freezeTask = null; }
    }

    public void restoreAfterDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!locked.contains(player.getUniqueId())) continue;
            boolean wasInvulnerable = previousInvulnerability.getOrDefault(player.getUniqueId(),
                player.getPersistentDataContainer().getOrDefault(
                    wasInvulnerableKey, PersistentDataType.BYTE, (byte) 0) == 1);
            player.setInvulnerable(wasInvulnerable);
        }
    }

    public void lock(Player player, String reason) {
        if (safeMode) return;
        UUID uuid = player.getUniqueId();
        if (locked.contains(uuid)) return;
        locked.add(uuid);
        Location safeLocation = findSafeLocation(player);
        lockLocations.put(uuid, safeLocation);
        long now = System.currentTimeMillis();
        lockTimes.put(uuid, now);
        boolean wasInvulnerable = player.isInvulnerable();
        previousInvulnerability.put(uuid, wasInvulnerable);
        var pdc = player.getPersistentDataContainer();
        pdc.set(lockedKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(worldKey, PersistentDataType.STRING, safeLocation.getWorld().getUID().toString());
        pdc.set(xKey, PersistentDataType.DOUBLE, safeLocation.getX());
        pdc.set(yKey, PersistentDataType.DOUBLE, safeLocation.getY());
        pdc.set(zKey, PersistentDataType.DOUBLE, safeLocation.getZ());
        pdc.set(lockedAtKey, PersistentDataType.LONG, now);
        pdc.set(wasInvulnerableKey, PersistentDataType.BYTE, (byte) (wasInvulnerable ? 1 : 0));
        player.setInvulnerable(true);
        if (!player.getLocation().getWorld().equals(safeLocation.getWorld())
            || player.getLocation().distanceSquared(safeLocation) > 0.25) {
            player.teleport(safeLocation);
        }
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
        Boolean wasInvulnerable = previousInvulnerability.remove(uuid);
        if (wasInvulnerable == null) {
            wasInvulnerable = player.getPersistentDataContainer().getOrDefault(
                wasInvulnerableKey, PersistentDataType.BYTE, (byte) 0) == 1;
        }
        player.setInvulnerable(wasInvulnerable);
        clearPersistentLockout(player);
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
            if (player == null || !player.isOnline()) continue;
            player.setInvulnerable(true);
            Location lockLoc = lockLocations.get(uuid);
            if (lockLoc != null && player.getLocation().distanceSquared(lockLoc) > 0.25) {
                player.teleport(lockLoc);
            }
        }
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var pdc = player.getPersistentDataContainer();
        if (pdc.getOrDefault(lockedKey, PersistentDataType.BYTE, (byte) 0) != 1) return;
        if (safeMode) {
            player.setInvulnerable(pdc.getOrDefault(wasInvulnerableKey, PersistentDataType.BYTE, (byte) 0) == 1);
            clearPersistentLockout(player);
            return;
        }
        UUID uuid = player.getUniqueId();
        long persistedAt = pdc.getOrDefault(lockedAtKey, PersistentDataType.LONG, System.currentTimeMillis());
        if (System.currentTimeMillis() - persistedAt > autoReleaseMs) {
            player.setInvulnerable(pdc.getOrDefault(wasInvulnerableKey, PersistentDataType.BYTE, (byte) 0) == 1);
            clearPersistentLockout(player);
            return;
        }
        String worldId = player.getPersistentDataContainer().get(worldKey, PersistentDataType.STRING);
        World world = null;
        if (worldId != null) {
            try {
                world = Bukkit.getWorld(UUID.fromString(worldId));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid persisted lockout world for " + player.getName());
            }
        }
        if (world == null) world = player.getWorld();
        double x = player.getPersistentDataContainer().getOrDefault(xKey, PersistentDataType.DOUBLE,
            player.getLocation().getX());
        double y = player.getPersistentDataContainer().getOrDefault(yKey, PersistentDataType.DOUBLE,
            player.getLocation().getY());
        double z = player.getPersistentDataContainer().getOrDefault(zKey, PersistentDataType.DOUBLE,
            player.getLocation().getZ());
        Location saved = new Location(world, x, y, z);
        Location safe = isSafe(saved) ? saved : findSafeLocation(player);
        lockLocations.put(uuid, safe);
        lockTimes.put(uuid, persistedAt);
        previousInvulnerability.put(uuid, player.getPersistentDataContainer().getOrDefault(
            wasInvulnerableKey, PersistentDataType.BYTE, (byte) 0) == 1);
        locked.add(uuid);
        player.setInvulnerable(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.teleport(safe);
        });
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
        // Persistent state is intentionally retained and reapplied on the next join.
    }

    private Location findSafeLocation(Player player) {
        Location current = player.getLocation();
        if (isSafe(current)) return current.clone();
        World world = current.getWorld();
        Location spawn = world.getSpawnLocation();
        if (isSafe(spawn)) return spawn;
        int baseX = spawn.getBlockX();
        int baseZ = spawn.getBlockZ();
        for (int radius = 1; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    for (int dy = 0; dy <= 3; dy++) {
                        Location candidate = new Location(world, x + 0.5, spawn.getBlockY() + dy, z + 0.5);
                        if (isSafe(candidate)) return candidate;
                    }
                }
            }
        }
        return current.clone();
    }

    private boolean isSafe(Location location) {
        if (location.getWorld() == null) return false;
        World world = location.getWorld();
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        return world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ()).isPassable()
            && world.getBlockAt(location.getBlockX(), location.getBlockY() + 1, location.getBlockZ()).isPassable()
            && world.getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ()).getType().isSolid();
    }

    private void clearPersistentLockout(Player player) {
        var pdc = player.getPersistentDataContainer();
        pdc.remove(lockedKey);
        pdc.remove(worldKey);
        pdc.remove(xKey);
        pdc.remove(yKey);
        pdc.remove(zKey);
        pdc.remove(lockedAtKey);
        pdc.remove(wasInvulnerableKey);
    }
}
