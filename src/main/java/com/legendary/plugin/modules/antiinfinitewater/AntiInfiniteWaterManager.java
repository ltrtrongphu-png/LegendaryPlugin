package com.legendary.plugin.modules.antiinfinitewater;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Optional per-player, per-chunk water-bucket rate limiter. */
public final class AntiInfiniteWaterManager {

    private record RateLimitKey(UUID playerId, UUID worldId, int chunkX, int chunkZ) {}

    private final LegendaryPlugin plugin;
    private final ConcurrentHashMap<RateLimitKey, long[]> playerPlaceTimes = new ConcurrentHashMap<>();
    private BukkitTask cleanupTask;
    private boolean bucketRateLimitEnabled = true;
    private int maxBucketPlacesPerWindow = 10;
    private long rateLimitWindowMs = 5000;
    private boolean enabled;

    public AntiInfiniteWaterManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        var config = plugin.getConfig();
        bucketRateLimitEnabled = config.getBoolean("anti-infinite-water.bucket-rate-limit-enabled", true);
        maxBucketPlacesPerWindow = Math.max(1, config.getInt("anti-infinite-water.max-bucket-places-per-window", 10));
        rateLimitWindowMs = Math.max(1000L, config.getLong("anti-infinite-water.rate-limit-window-ms", 5000));
    }

    public void start() {
        if (cleanupTask != null) cleanupTask.cancel();
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupRateLimits, 600L, 600L);
        enabled = true;
        plugin.getLogger().info("[WaterFlowLimiter] Active — bucket rate limiting: " + bucketRateLimitEnabled);
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        playerPlaceTimes.clear();
        enabled = false;
    }

    public boolean isBucketRateLimitEnabled() { return bucketRateLimitEnabled; }
    public boolean isEnabled() { return enabled; }

    public boolean checkRateLimit(UUID playerId, Location location) {
        if (location.getWorld() == null) return true;
        RateLimitKey key = new RateLimitKey(playerId, location.getWorld().getUID(),
            location.getBlockX() >> 4, location.getBlockZ() >> 4);
        long now = System.currentTimeMillis();
        long[] previous = playerPlaceTimes.getOrDefault(key, new long[0]);
        long[] recent = Arrays.stream(previous)
            .filter(time -> now - time < rateLimitWindowMs)
            .toArray();
        if (recent.length >= maxBucketPlacesPerWindow) {
            playerPlaceTimes.put(key, recent);
            return false;
        }
        long[] updated = Arrays.copyOf(recent, recent.length + 1);
        updated[recent.length] = now;
        playerPlaceTimes.put(key, updated);
        return true;
    }

    private void cleanupRateLimits() {
        long now = System.currentTimeMillis();
        playerPlaceTimes.entrySet().removeIf(entry ->
            Arrays.stream(entry.getValue()).noneMatch(time -> now - time < rateLimitWindowMs));
    }
}
