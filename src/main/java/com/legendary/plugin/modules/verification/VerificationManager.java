package com.legendary.plugin.modules.verification;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
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
    private final Map<UUID, Location> originalLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> previousInvulnerable = new ConcurrentHashMap<>();
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
        double configuredDistance = plugin.getConfig().getDouble("verification.required-distance", 3.0);
        double maximumDistance = 4.0; // 9x9 platform, centered on the spawn block
        if (!Double.isFinite(configuredDistance) || configuredDistance <= 0 || configuredDistance > maximumDistance) {
            plugin.getLogger().warning("verification.required-distance must be in (0, 4.0]; using a safe value.");
            configuredDistance = Math.max(0.1, Math.min(maximumDistance,
                Double.isFinite(configuredDistance) ? configuredDistance : 3.0));
        }
        requiredDistance = configuredDistance;
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
        // Keep the verification path inside a bounded, enclosed platform.
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
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                var block = center.getWorld().getBlockAt(center.getBlockX() + dx, y, center.getBlockZ() + dz);
                if (block.getType() == Material.AIR) block.setType(Material.STONE);
            }
        }
        for (int offset = -5; offset <= 5; offset++) {
            for (int dy = 0; dy <= 2; dy++) {
                placeBarrier(center, offset, dy, -5);
                placeBarrier(center, offset, dy, 5);
                if (offset != -5 && offset != 5) {
                    placeBarrier(center, -5, dy, offset);
                    placeBarrier(center, 5, dy, offset);
                }
            }
        }
    }

    private void placeBarrier(Location center, int dx, int dy, int dz) {
        var block = center.getWorld().getBlockAt(
            center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
        if (block.getType() == Material.AIR) block.setType(Material.BARRIER);
    }

    public void stop() {
        for (BukkitTask task : timeoutTasks.values()) task.cancel();
        timeoutTasks.clear();
        for (Map.Entry<UUID, Location> entry : originalLocations.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            player.teleport(entry.getValue());
            Boolean wasInvulnerable = previousInvulnerable.remove(entry.getKey());
            if (wasInvulnerable != null) player.setInvulnerable(wasInvulnerable);
            clearStoredOriginalLocation(player);
        }
        verifyStartLocation.clear();
        originalLocations.clear();
        previousInvulnerable.clear();
    }

    public boolean isVerifying(Player player) {
        return verifyStartLocation.containsKey(player.getUniqueId());
    }

    public void beginVerification(Player player) {
        if (limboWorld == null || verifySpawn == null || player.hasPermission("legendary.verification.bypass")) return;
        Location original = player.getLocation().clone();
        originalLocations.put(player.getUniqueId(), original);
        storeOriginalLocation(player, original);
        verifyStartLocation.put(player.getUniqueId(), verifySpawn.clone());
        previousInvulnerable.put(player.getUniqueId(), player.isInvulnerable());
        player.setInvulnerable(true);
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
            && horizontalDistanceSquared(player.getLocation(), start) >= requiredDistance * requiredDistance) {
            completeAndReleasePlayer(player);
            return true;
        }
        return false;
    }

    static double horizontalDistanceSquared(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
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
        Location original = originalLocations.get(player.getUniqueId());
        Boolean wasInvulnerable = previousInvulnerable.remove(player.getUniqueId());
        finishVerification(player.getUniqueId());
        if (wasInvulnerable != null) player.setInvulnerable(wasInvulnerable);
        if (original == null || !plugin.getConfig().getBoolean("verification.return-to-original-location", true)) {
            original = resolveReleaseLocation();
        }
        player.teleport(original);
        clearStoredOriginalLocation(player);
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
        originalLocations.remove(uuid);
        BukkitTask task = timeoutTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    public void cancel(Player player) {
        Boolean wasInvulnerable = previousInvulnerable.remove(player.getUniqueId());
        if (wasInvulnerable != null) player.setInvulnerable(wasInvulnerable);
        finishVerification(player.getUniqueId());
    }

    public void restoreOriginalLocation(Player player) {
        var pdc = player.getPersistentDataContainer();
        String worldName = pdc.get(key("world"), PersistentDataType.STRING);
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Cannot restore verification location for " + player.getName()
                + ": world '" + worldName + "' is unavailable.");
            return;
        }
        Double x = pdc.get(key("x"), PersistentDataType.DOUBLE);
        Double y = pdc.get(key("y"), PersistentDataType.DOUBLE);
        Double z = pdc.get(key("z"), PersistentDataType.DOUBLE);
        Float yaw = pdc.get(key("yaw"), PersistentDataType.FLOAT);
        Float pitch = pdc.get(key("pitch"), PersistentDataType.FLOAT);
        if (x == null || y == null || z == null) {
            clearStoredOriginalLocation(player);
            return;
        }
        Location original = new Location(world, x, y, z, yaw == null ? 0 : yaw, pitch == null ? 0 : pitch);
        player.teleport(original);
        clearStoredOriginalLocation(player);
    }

    public void returnToLimbo(Player player) {
        if (verifySpawn != null && isVerifying(player)) player.teleport(verifySpawn);
    }

    private void storeOriginalLocation(Player player, Location location) {
        var pdc = player.getPersistentDataContainer();
        pdc.set(key("world"), PersistentDataType.STRING, location.getWorld().getName());
        pdc.set(key("x"), PersistentDataType.DOUBLE, location.getX());
        pdc.set(key("y"), PersistentDataType.DOUBLE, location.getY());
        pdc.set(key("z"), PersistentDataType.DOUBLE, location.getZ());
        pdc.set(key("yaw"), PersistentDataType.FLOAT, location.getYaw());
        pdc.set(key("pitch"), PersistentDataType.FLOAT, location.getPitch());
    }

    private void clearStoredOriginalLocation(Player player) {
        var pdc = player.getPersistentDataContainer();
        for (String field : new String[] {"world", "x", "y", "z", "yaw", "pitch"}) {
            pdc.remove(key(field));
        }
    }

    private NamespacedKey key(String field) {
        return new NamespacedKey(plugin, "verification-location-" + field);
    }
}
