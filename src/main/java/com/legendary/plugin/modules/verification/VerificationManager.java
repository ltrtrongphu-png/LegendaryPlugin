package com.legendary.plugin.modules.verification;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds joining players in a lightweight void-world limbo until they
 * perform a trivial human-only action (move a short distance), which
 * filters out simple join-bots before they ever touch the real world.
 * Ported from BaB / SmartOptimizer's VerificationManager (the two legacy
 * implementations were near-duplicates; merged into one here).
 */
public final class VerificationManager {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Location> verifyStartLocation = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();
    private World limboWorld;
    private Location verifySpawn;
    private Location clickBlockLocation;
    private double requiredDistance;
    private int timeoutSeconds;
    private String verificationMethod;

    public VerificationManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        requiredDistance = plugin.getConfig().getDouble("verification.required-distance", 3.0);
        timeoutSeconds = plugin.getConfig().getInt("verification.timeout-seconds", 30);
        verificationMethod = plugin.getConfig().getString("verification.method", "move").toLowerCase();
        if (!verificationMethod.equals("move") && !verificationMethod.equals("clickblock")) {
            plugin.getLogger().warning("Unknown verification.method '" + verificationMethod + "', falling back to 'move'.");
            verificationMethod = "move";
        }
    }

    public void start() {
        String worldName = plugin.getConfig().getString("verification.limbo-world-name", "legendary_limbo");
        limboWorld = Bukkit.getWorld(worldName);
        if (limboWorld == null) {
            WorldCreator creator = new WorldCreator(worldName)
                .generator(new VoidGenerator())
                .generateStructures(false);
            limboWorld = Bukkit.createWorld(creator);
        }
        if (limboWorld == null) return; // world creation failed (e.g. disabled by another plugin) - fail open, log only
        // The void generator produces zero blocks, so we build a small fixed platform once
        // rather than trusting an auto-computed spawn (which has nothing to sit on in a void world).
        verifySpawn = new Location(limboWorld, 0.5, 65.0, 0.5, 0f, 0f);
        limboWorld.setSpawnLocation(verifySpawn.getBlockX(), verifySpawn.getBlockY(), verifySpawn.getBlockZ());
        buildPlatform(verifySpawn);

        if ("clickblock".equals(verificationMethod)) {
            clickBlockLocation = verifySpawn.clone().add(2, 0, 0);
            var plate = clickBlockLocation.getBlock();
            if (plate.getType() != Material.STONE_PRESSURE_PLATE) {
                plate.getRelative(org.bukkit.block.BlockFace.DOWN).setType(Material.STONE);
                plate.setType(Material.STONE_PRESSURE_PLATE);
            }
        }
    }

    private void buildPlatform(Location center) {
        int y = center.getBlockY() - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                var block = center.getWorld().getBlockAt(center.getBlockX() + dx, y, center.getBlockZ() + dz);
                if (block.getType() == Material.AIR) block.setType(Material.STONE);
            }
        }
    }

    public void stop() {
        for (BukkitTask task : timeoutTasks.values()) task.cancel();
        timeoutTasks.clear();
        verifyStartLocation.clear();
    }

    public boolean isVerifying(Player player) {
        return verifyStartLocation.containsKey(player.getUniqueId());
    }

    public void beginVerification(Player player) {
        if (limboWorld == null || verifySpawn == null || player.hasPermission("legendary.verification.bypass")) return;
        verifyStartLocation.put(player.getUniqueId(), verifySpawn.clone());
        player.teleport(verifySpawn);

        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isVerifying(player) && player.isOnline()) {
                player.kick(com.legendary.plugin.util.Text.of("<red>Verification timed out - please rejoin."));
            }
            finishVerification(player.getUniqueId());
        }, timeoutSeconds * 20L);
        timeoutTasks.put(player.getUniqueId(), timeout);
    }

    public String getVerificationMethod() { return verificationMethod; }
    public Location getClickBlockLocation() { return clickBlockLocation; }

    /** Call from the move listener (method=move); returns true once verification just completed. */
    public boolean tryComplete(Player player) {
        if (!"move".equals(verificationMethod)) return false;
        Location start = verifyStartLocation.get(player.getUniqueId());
        if (start == null) return false;
        if (player.getLocation().getWorld() != null && start.getWorld() != null
            && player.getLocation().getWorld().equals(start.getWorld())
            && player.getLocation().distanceSquared(start) >= requiredDistance * requiredDistance) {
            completeAndReleasePlayer(player);
            return true;
        }
        return false;
    }

    /** Call from the interact listener (method=clickblock) when the player steps on/clicks the marked block. */
    public boolean tryCompleteClick(Player player, Location interactedBlockLoc) {
        if (!"clickblock".equals(verificationMethod) || clickBlockLocation == null) return false;
        if (!isVerifying(player)) return false;
        if (interactedBlockLoc.getWorld() != null && clickBlockLocation.getWorld() != null
            && interactedBlockLoc.getWorld().equals(clickBlockLocation.getWorld())
            && interactedBlockLoc.getBlockX() == clickBlockLocation.getBlockX()
            && interactedBlockLoc.getBlockY() == clickBlockLocation.getBlockY()
            && interactedBlockLoc.getBlockZ() == clickBlockLocation.getBlockZ()) {
            completeAndReleasePlayer(player);
            return true;
        }
        return false;
    }

    private void completeAndReleasePlayer(Player player) {
        finishVerification(player.getUniqueId());
        // Always release to a fixed, admin-configured location - NEVER wherever the player
        // happened to be standing before verification - so a join-bot/exploit attempt can't
        // use "wherever I was last" as a way to land somewhere sensitive. Defaults to the
        // default world's spawn point if no override is configured.
        player.teleport(resolveReleaseLocation());
    }

    public Location resolveReleaseLocation() {
        String worldName = plugin.getConfig().getString("verification.release-location.world", "");
        World world = worldName.isBlank() ? plugin.getServer().getWorlds().get(0) : Bukkit.getWorld(worldName);
        if (world == null) world = plugin.getServer().getWorlds().get(0);

        if (!plugin.getConfig().isSet("verification.release-location.x")) {
            return world.getSpawnLocation();
        }
        double x = plugin.getConfig().getDouble("verification.release-location.x");
        double y = plugin.getConfig().getDouble("verification.release-location.y");
        double z = plugin.getConfig().getDouble("verification.release-location.z");
        float yaw = (float) plugin.getConfig().getDouble("verification.release-location.yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble("verification.release-location.pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void finishVerification(UUID uuid) {
        verifyStartLocation.remove(uuid);
        BukkitTask task = timeoutTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    public void cancel(Player player) {
        finishVerification(player.getUniqueId());
    }
}
