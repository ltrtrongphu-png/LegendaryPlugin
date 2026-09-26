package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Merges nearby stacks of the same dropped-item type within a chunk to
 * cut down on entity count during lag spikes. Ported from
 * SmartOptimizer.ItemMergeTask.
 *
 * Performance note: this class no longer walks the world/chunk tree
 * itself - {@link EntitySweepTask} does that ONE scan per tick and hands
 * every sub-system its pre-filtered per-chunk list, instead of each of
 * the 5 optimization sub-systems re-scanning every loaded chunk's entity
 * list independently (5x redundant O(worlds x chunks x entities) work).
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

    /** Called once per loaded chunk by EntitySweepTask with that chunk's dropped items already collected. */
    public void mergeInChunk(List<Item> items) {
        OptimizationEngine.Settings settings = current;
        if (settings == null || settings.itemMergeRadius <= 0) return;
        double radius = settings.itemMergeRadius;
        if (items.size() < 2 || items.size() > MAX_ITEMS_PER_CHUNK) return;

        for (int i = 0; i < items.size(); i++) {
            Item a = items.get(i);
            if (a.isDead() || isProtected(a)) continue;
            for (int j = i + 1; j < items.size(); j++) {
                Item b = items.get(j);
                if (b.isDead() || isProtected(b)) continue;
                if (!a.getLocation().getWorld().equals(b.getLocation().getWorld())) continue;
                if (a.getLocation().distanceSquared(b.getLocation()) > radius * radius) continue;

                ItemStack stackA = a.getItemStack();
                ItemStack stackB = b.getItemStack();
                if (!stackA.isSimilar(stackB)) continue;

                int combined = stackA.getAmount() + stackB.getAmount();
                int maxStack = stackA.getMaxStackSize();
                if (combined > maxStack) continue; // keep simple: only merge if it fits in one stack

                stackA.setAmount(combined);
                a.setItemStack(stackA);
                b.remove();
            }
        }
    }

    private boolean isProtected(Item entity) {
        return ProtectionRules.isProtectedItem(entity, skipNamedOrEnchanted);
    }
}
