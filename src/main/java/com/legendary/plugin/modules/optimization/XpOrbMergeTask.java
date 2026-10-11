package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Location;
import org.bukkit.entity.ExperienceOrb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges nearby XP orbs in a chunk into a single orb carrying the summed
 * experience value, applied more aggressively during lag. Fed a pre-scanned
 * list by {@link EntitySweepTask}.
 *
 * PERFORMANCE UPGRADE: same spatial hash grid optimization as
 * {@link ItemMergeTask} - replaces O(n^2) pairwise distance checks with
 * O(n) by bucketing orbs by block position / merge radius.
 */
public final class XpOrbMergeTask {

    private static final int MAX_ORBS_PER_CHUNK = 100;

    private final LegendaryPlugin plugin;
    private volatile OptimizationEngine.Settings current;

    public XpOrbMergeTask(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateSettings(OptimizationEngine.Settings settings) {
        this.current = settings;
    }

    public void mergeInChunk(List<ExperienceOrb> orbs) {
        OptimizationEngine.Settings settings = current;
        if (settings == null || settings.itemMergeRadius <= 0) return;
        double radius = settings.itemMergeRadius;
        if (orbs.size() < 2 || orbs.size() > MAX_ORBS_PER_CHUNK) return;

        double radiusSq = radius * radius;
        int bucketSize = Math.max(1, (int) Math.ceil(radius));
        Map<Long, List<ExperienceOrb>> grid = new HashMap<>(orbs.size() * 2);

        for (ExperienceOrb orb : orbs) {
            if (orb.isDead()) continue;
            Location loc = orb.getLocation();
            long key = bucketKey(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), bucketSize);
            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(orb);
        }

        for (Map.Entry<Long, List<ExperienceOrb>> entry : grid.entrySet()) {
            List<ExperienceOrb> bucket = entry.getValue();
            if (bucket.size() < 2) continue;

            long bx = unpackX(entry.getKey());
            long by = unpackY(entry.getKey());
            long bz = unpackZ(entry.getKey());

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long neighborKey = packKey(bx + dx, by, bz + dz);
                    List<ExperienceOrb> neighborBucket = grid.get(neighborKey);
                    if (neighborBucket == null) continue;
                    mergeBuckets(bucket, neighborBucket, radiusSq);
                }
            }
            mergeBuckets(bucket, bucket, radiusSq);
        }
    }

    private void mergeBuckets(List<ExperienceOrb> a, List<ExperienceOrb> b, double radiusSq) {
        for (int i = 0; i < a.size(); i++) {
            ExperienceOrb orbA = a.get(i);
            if (orbA.isDead()) continue;
            for (int j = (a == b ? i + 1 : 0); j < b.size(); j++) {
                ExperienceOrb orbB = b.get(j);
                if (orbB.isDead() || orbA == orbB) continue;
                if (orbA.getLocation().distanceSquared(orbB.getLocation()) > radiusSq) continue;
                orbA.setExperience(orbA.getExperience() + orbB.getExperience());
                orbB.remove();
            }
        }
    }

    private static long packKey(long x, long y, long z) {
        return (x & 0x3FFFFFF) | ((y & 0xFFF) << 26) | ((z & 0x3FFFFFF) << 38);
    }

    private static long bucketKey(int x, int y, int z, int bucketSize) {
        return packKey(x / bucketSize, y / bucketSize, z / bucketSize);
    }

    private static long unpackX(long key) { return key & 0x3FFFFFF; }
    private static long unpackY(long key) { return (key >> 26) & 0xFFF; }
    private static long unpackZ(long key) { return (key >> 38) & 0x3FFFFFF; }
}
