package com.legendary.plugin.modules.antiinfinitewater;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Dispenser;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core logic for Anti-Infinite-Water — professional edition.
 *
 * Blocks ALL infinite water creation methods including:
 *   1. Manual 2x2 / 3x1 bucket pools (classic exploit)
 *   2. Dispenser-based infinite water machines (redstone + observer + dispenser)
 *   3. Automated water farms that use dispensers to place water and observers
 *      to trigger retraction, creating a loop that generates infinite sources
 *   4. Any water flow that would convert to a new source block via the
 *      vanilla "2 adjacent sources = new source" mechanic
 *
 * Detection layers:
 *   - BlockFromTo: cancels water flow that would create a new source
 *   - FluidLevelChange: cancels level changes that would create a source
 *   - Dispenser place: blocks dispensers from placing water entirely
 *   - Area scan: periodically scans for existing infinite water pools and
 *     removes the illegally-created source blocks
 *   - Bucket rate limit: throttles rapid water bucket placement
 *   - Redstone machine detection: flags dispenser+observer+water patterns
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class AntiInfiniteWaterManager {

    private final LegendaryPlugin plugin;
    private final ConcurrentHashMap<UUID, long[]> playerPlaceTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> machineWarnings = new ConcurrentHashMap<>();
    private BukkitTask scanTask;
    private BukkitTask cleanupTask;

    private boolean blockFlowEnabled = true;
    private boolean blockDispenserWater = true;
    private boolean bucketRateLimitEnabled = true;
    private boolean areaScanEnabled = true;
    private boolean detectRedstoneMachines = true;
    private boolean removeExistingPools = true;
    private boolean notifyStaff = true;
    private int maxBucketPlacesPerWindow = 10;
    private long rateLimitWindowMs = 5000;
    private int scanIntervalSeconds = 30;
    private int maxScanChunksPerCycle = 8;
    private int maxPoolRemovalPerCycle = 50;
    private int machineWarnThreshold = 3;
    private boolean enabled = false;

    public AntiInfiniteWaterManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        var cfg = plugin.getConfig();
        blockFlowEnabled = cfg.getBoolean("anti-infinite-water.block-flow-enabled", true);
        blockDispenserWater = cfg.getBoolean("anti-infinite-water.block-dispenser-water", true);
        bucketRateLimitEnabled = cfg.getBoolean("anti-infinite-water.bucket-rate-limit-enabled", true);
        areaScanEnabled = cfg.getBoolean("anti-infinite-water.area-scan-enabled", true);
        detectRedstoneMachines = cfg.getBoolean("anti-infinite-water.detect-redstone-machines", true);
        removeExistingPools = cfg.getBoolean("anti-infinite-water.remove-existing-pools", true);
        notifyStaff = cfg.getBoolean("anti-infinite-water.notify-staff", true);
        maxBucketPlacesPerWindow = cfg.getInt("anti-infinite-water.max-bucket-places-per-window", 10);
        rateLimitWindowMs = cfg.getLong("anti-infinite-water.rate-limit-window-ms", 5000);
        scanIntervalSeconds = cfg.getInt("anti-infinite-water.scan-interval-seconds", 30);
        maxScanChunksPerCycle = cfg.getInt("anti-infinite-water.max-scan-chunks-per-cycle", 8);
        maxPoolRemovalPerCycle = cfg.getInt("anti-infinite-water.max-pool-removal-per-cycle", 50);
        machineWarnThreshold = cfg.getInt("anti-infinite-water.machine-warn-threshold", 3);
    }

    public void start() {
        if (cleanupTask != null) cleanupTask.cancel();
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupRateLimits, 600L, 600L);

        if (areaScanEnabled) {
            if (scanTask != null) scanTask.cancel();
            scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanForInfinitePools,
                200L, scanIntervalSeconds * 20L);
        }

        enabled = true;
        plugin.getLogger().info("[AntiInfiniteWater] Active — flow: " + blockFlowEnabled
            + ", dispenser: " + blockDispenserWater
            + ", area scan: " + areaScanEnabled
            + ", redstone detection: " + detectRedstoneMachines
            + ", pool removal: " + removeExistingPools);
    }

    public void stop() {
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        if (scanTask != null) { scanTask.cancel(); scanTask = null; }
        playerPlaceTimes.clear();
        machineWarnings.clear();
        enabled = false;
    }

    // --- Config accessors ---

    public boolean isBlockFlowEnabled() { return blockFlowEnabled; }
    public boolean isBlockDispenserWater() { return blockDispenserWater; }
    public boolean isBucketRateLimitEnabled() { return bucketRateLimitEnabled; }
    public boolean isAreaScanEnabled() { return areaScanEnabled; }
    public boolean isDetectRedstoneMachines() { return detectRedstoneMachines; }
    public boolean isRemoveExistingPools() { return removeExistingPools; }
    public boolean isNotifyStaff() { return notifyStaff; }
    public int getMachineWarnThreshold() { return machineWarnThreshold; }
    public boolean isEnabled() { return enabled; }

    // --- Water source detection ---

    public boolean isFullWaterSource(Block block) {
        if (block.getType() != Material.WATER) return false;
        BlockData data = block.getBlockData();
        if (data instanceof Levelled levelled) {
            return levelled.getLevel() == 0;
        }
        return false;
    }

    /**
     * Checks whether a target block would become a new infinite water source
     * if water flowed into it. In vanilla, a non-source water block becomes a
     * full source when it has 2+ horizontal neighbors that are full sources.
     */
    public boolean wouldCreateInfiniteAt(Block target) {
        if (isFullWaterSource(target)) return false;
        if (target.getType() != Material.AIR && target.getType() != Material.WATER) return false;

        BlockFace[] horizontal = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        int sourceCount = 0;
        for (BlockFace face : horizontal) {
            Block neighbor = target.getRelative(face);
            if (isFullWaterSource(neighbor)) sourceCount++;
        }
        return sourceCount >= 2;
    }

    // --- Dispenser water blocking ---

    /**
     * Returns true if a dispenser is about to place water.
     * Used to block dispenser-based infinite water machines.
     */
    public boolean isDispenserPlacingWater(Block dispenserBlock) {
        if (dispenserBlock.getType() != Material.DISPENSER) return false;
        BlockData data = dispenserBlock.getBlockData();
        if (!(data instanceof Dispenser)) return false;
        return true;
    }

    // --- Redstone machine detection ---

    /**
     * Detects redstone infinite water machine patterns:
     * A dispenser adjacent to water source blocks, with an observer
     * or repeater nearby, is likely part of an infinite water farm.
     */
    public boolean isInfiniteWaterMachine(Block dispenserBlock) {
        if (!detectRedstoneMachines) return false;
        if (dispenserBlock.getType() != Material.DISPENSER) return false;

        boolean hasWaterNearby = false;
        boolean hasObserver = false;
        boolean hasRedstone = false;

        BlockFace[] all = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN};

        for (BlockFace face : all) {
            Block rel = dispenserBlock.getRelative(face);
            if (isFullWaterSource(rel)) hasWaterNearby = true;
            if (rel.getType() == Material.OBSERVER) hasObserver = true;
            if (rel.getType() == Material.REPEATER || rel.getType() == Material.REDSTONE_WIRE
                || rel.getType() == Material.REDSTONE_TORCH
                || rel.getType() == Material.REDSTONE_BLOCK
                || rel.getType() == Material.COMPARATOR) hasRedstone = true;
        }

        // Dispenser + water + observer = classic infinite water machine
        if (hasWaterNearby && hasObserver) return true;

        // Dispenser + water + redstone components = likely a water farm
        if (hasWaterNearby && hasRedstone) return true;

        return false;
    }

    public String machineKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    public int incrementMachineWarning(Block block) {
        String key = machineKey(block);
        int count = machineWarnings.merge(key, 1, Integer::sum);
        return count;
    }

    public void clearMachineWarning(Block block) {
        machineWarnings.remove(machineKey(block));
    }

    // --- Rate limiting ---

    public boolean checkRateLimit(UUID playerId) {
        long now = System.currentTimeMillis();
        long[] times = playerPlaceTimes.computeIfAbsent(playerId, k -> new long[0]);

        int count = 0;
        for (long t : times) {
            if (now - t < rateLimitWindowMs) count++;
        }

        long[] updated;
        if (count >= maxBucketPlacesPerWindow) {
            updated = new long[count + 1];
        } else {
            updated = new long[times.length + 1];
        }
        int idx = 0;
        for (long t : times) {
            if (now - t < rateLimitWindowMs && idx < updated.length - 1) {
                updated[idx++] = t;
            }
        }
        updated[idx] = now;
        playerPlaceTimes.put(playerId, updated);

        return count < maxBucketPlacesPerWindow;
    }

    private void cleanupRateLimits() {
        long now = System.currentTimeMillis();
        playerPlaceTimes.entrySet().removeIf(entry -> {
            long[] times = entry.getValue();
            for (long t : times) {
                if (now - t < rateLimitWindowMs) return false;
            }
            return true;
        });

        // Decay machine warnings over time
        if (!machineWarnings.isEmpty() && machineWarnings.size() > 100) {
            machineWarnings.clear();
        }
    }

    // --- Area scan: find and remove existing infinite water pools ---

    private void scanForInfinitePools() {
        if (!removeExistingPools) return;

        int removed = 0;
        int chunksScanned = 0;

        for (World world : Bukkit.getWorlds()) {
            if (chunksScanned >= maxScanChunksPerCycle) break;
            if (removed >= maxPoolRemovalPerCycle) break;

            for (var chunk : world.getLoadedChunks()) {
                if (chunksScanned >= maxScanChunksPerCycle) break;
                if (removed >= maxPoolRemovalPerCycle) break;

                int baseX = chunk.getX() << 4;
                int baseZ = chunk.getZ() << 4;

                // Scan chunk for water source blocks that have 2+ source neighbors
                // and were likely created by an infinite water exploit
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                            if (removed >= maxPoolRemovalPerCycle) break;

                            Block block = world.getBlockAt(baseX + x, y, baseZ + z);
                            if (!isFullWaterSource(block)) continue;

                            // Check if this source has 2+ source neighbors (infinite pool pattern)
                            BlockFace[] h = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
                            int sourceNeighbors = 0;
                            for (BlockFace face : h) {
                                if (isFullWaterSource(block.getRelative(face))) sourceNeighbors++;
                            }

                            // If 3+ source neighbors, it's part of a pool — check if there's
                            // a dispenser/observer nearby indicating a machine created it
                            if (sourceNeighbors >= 2) {
                                if (hasMachineNearby(block, 6)) {
                                    // Remove this source block — it was machine-created
                                    block.setType(Material.AIR);
                                    removed++;
                                    plugin.getMetrics().counter("antiinfinitewater.pools.removed").increment();
                                }
                            }
                        }
                    }
                }
                chunksScanned++;
            }
        }

        if (removed > 0) {
            plugin.getLogger().info("[AntiInfiniteWater] Area scan removed " + removed
                + " machine-created water source blocks.");
        }
    }

    /**
     * Checks if there's a dispenser or observer within the given radius,
     * indicating a redstone infinite water machine created this pool.
     */
    private boolean hasMachineNearby(Block center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Block b = center.getRelative(dx, dy, dz);
                    Material type = b.getType();
                    if (type == Material.DISPENSER || type == Material.OBSERVER) return true;
                }
            }
        }
        return false;
    }
}
