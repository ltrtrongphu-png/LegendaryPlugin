package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges nearby stacks of the same dropped-item type within a chunk to
 * cut down on entity count during lag spikes. Fed a pre-filtered per-chunk
 * list by {@link EntitySweepTask}.
 *
 * PERFORMANCE UPGRADE: replaces the O(n^2) pairwise distance check with a
 * spatial hash grid. Items are bucketed by their block position divided by
 * the merge radius, so only items in the same or neighboring buckets need
 * to be compared. On chunks with many dropped items (e.g. a mob farm or
 * explosion), this reduces comparisons from n^2 to roughly O(n).
 */
public final class ItemMergeTask {

    private static final int MAX_ITEMS_PER_CHUNK = 100;

    private final LegendaryPlugin plugin;
    private volatile OptimizationEngine.Settings current;
    private final boolean skipNamedOrEnchanted;

    public ItemMergeTask(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.skipNamedOrEnchanted = plugin.getConfig().getBoolean("optimization.item-merge.skip-named-or-enchanted", true);
    }

    public void updateSettings(OptimizationEngine.Settings settings) {
        this.current = settings;
    }

    public void mergeInChunk(List<Item> items) {
        OptimizationEngine.Settings settings = current;
        if (settings == null || settings.itemMergeRadius <= 0) return;
        double radius = settings.itemMergeRadius;
        if (items.size() < 2 || items.size() > MAX_ITEMS_PER_CHUNK) return;

        double radiusSq = radius * radius;
        int bucketSize = Math.max(1, (int) Math.ceil(radius));
        Map<Long, List<Item>> grid = new HashMap<>(items.size() * 2);

        for (Item item : items) {
            if (item.isDead() || isProtected(item)) continue;
            Location loc = item.getLocation();
            long key = bucketKey(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), bucketSize);
            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        for (Map.Entry<Long, List<Item>> entry : grid.entrySet()) {
            List<Item> bucket = entry.getValue();
            if (bucket.size() < 2) continue;

            long bx = unpackX(entry.getKey());
            long by = unpackY(entry.getKey());
            long bz = unpackZ(entry.getKey());

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long neighborKey = packKey(bx + dx, by, bz + dz);
                    List<Item> neighborBucket = grid.get(neighborKey);
                    if (neighborBucket == null) continue;
                    mergeBuckets(bucket, neighborBucket, radiusSq);
                }
            }
            mergeBuckets(bucket, bucket, radiusSq);
        }
    }

    private void mergeBuckets(List<Item> a, List<Item> b, double radiusSq) {
        for (int i = 0; i < a.size(); i++) {
            Item itemA = a.get(i);
            if (itemA.isDead()) continue;
            for (int j = (a == b ? i + 1 : 0); j < b.size(); j++) {
                Item itemB = b.get(j);
                if (itemB.isDead() || itemA == itemB) continue;
                if (itemA.getLocation().distanceSquared(itemB.getLocation()) > radiusSq) continue;

                ItemStack stackA = itemA.getItemStack();
                ItemStack stackB = itemB.getItemStack();
                if (!stackA.isSimilar(stackB)) continue;

                int combined = stackA.getAmount() + stackB.getAmount();
                int maxStack = stackA.getMaxStackSize();
                if (combined > maxStack) continue;

                stackA.setAmount(combined);
                itemA.setItemStack(stackA);
                itemB.remove();
            }
        }
    }

    private boolean isProtected(Item entity) {
        return ProtectionRules.isProtectedItem(entity, skipNamedOrEnchanted);
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
