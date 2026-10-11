package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single combined world/chunk/entity scan per optimization tick for all five
 * entity-related sub-systems (item merge, XP merge, mob cap, armor stand
 * limit, falling block limit). Turns 5x O(worlds x chunks x entities) into 1x.
 *
 * PERFORMANCE UPGRADE: adds a chunk skip-cache so chunks that had zero
 * target entities on the last sweep are skipped on the next sweep (with a
 * periodic re-check). On servers with many loaded but mostly-empty chunks
 * (common at scale), this avoids re-iterating entity arrays for chunks
 * that consistently have nothing to act on.
 */
public final class EntitySweepTask {

    private final LegendaryPlugin plugin;
    private final ItemMergeTask itemMergeTask;
    private final XpOrbMergeTask xpOrbMergeTask;
    private final MobCapEnforcer mobCapEnforcer;
    private final ArmorStandLimiter armorStandLimiter;
    private final FallingBlockLimiter fallingBlockLimiter;

    private final Map<String, Long> emptyChunkCache = new HashMap<>();
    private static final long CACHE_TTL_MS = 60000;
    private long lastCacheSweep = 0;

    public EntitySweepTask(LegendaryPlugin plugin, ItemMergeTask itemMergeTask, XpOrbMergeTask xpOrbMergeTask,
                            MobCapEnforcer mobCapEnforcer, ArmorStandLimiter armorStandLimiter,
                            FallingBlockLimiter fallingBlockLimiter) {
        this.plugin = plugin;
        this.itemMergeTask = itemMergeTask;
        this.xpOrbMergeTask = xpOrbMergeTask;
        this.mobCapEnforcer = mobCapEnforcer;
        this.armorStandLimiter = armorStandLimiter;
        this.fallingBlockLimiter = fallingBlockLimiter;
    }

    public void run(boolean itemMergeOn, boolean xpMergeOn, boolean mobCapOn, boolean armorStandOn, boolean fallingBlockOn) {
        if (!(itemMergeOn || xpMergeOn || mobCapOn || armorStandOn || fallingBlockOn)) return;

        long now = System.currentTimeMillis();
        boolean anyLevelActive = mobCapEnforcer.getCurrentLevel() != OptimizationEngine.Level.NORMAL
            || armorStandLimiter.getCurrentLevel() != OptimizationEngine.Level.NORMAL
            || fallingBlockLimiter.getCurrentLevel() != OptimizationEngine.Level.NORMAL;

        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                String chunkKey = world.getName() + ":" + chunk.getX() + ":" + chunk.getZ();

                if (!anyLevelActive && itemMergeOn && xpMergeOn) {
                    Long lastEmpty = emptyChunkCache.get(chunkKey);
                    if (lastEmpty != null && now - lastEmpty < CACHE_TTL_MS) continue;
                }

                List<Item> items = itemMergeOn ? new ArrayList<>() : null;
                List<ExperienceOrb> orbs = xpMergeOn ? new ArrayList<>() : null;
                List<Monster> monsters = mobCapOn ? new ArrayList<>() : null;
                List<ArmorStand> stands = armorStandOn ? new ArrayList<>() : null;
                List<FallingBlock> fallingBlocks = fallingBlockOn ? new ArrayList<>() : null;

                for (Entity entity : chunk.getEntities()) {
                    if (items != null && entity instanceof Item item) {
                        items.add(item);
                    } else if (orbs != null && entity instanceof ExperienceOrb orb) {
                        orbs.add(orb);
                    } else if (monsters != null && entity instanceof Monster monster && !mobCapEnforcer.isProtected(monster)) {
                        monsters.add(monster);
                    } else if (stands != null && entity instanceof ArmorStand stand && armorStandLimiter.isUnprotected(stand)) {
                        stands.add(stand);
                    } else if (fallingBlocks != null && entity instanceof FallingBlock fb) {
                        fallingBlocks.add(fb);
                    }
                }

                boolean hasAny = (items != null && !items.isEmpty())
                    || (orbs != null && !orbs.isEmpty())
                    || (monsters != null && !monsters.isEmpty())
                    || (stands != null && !stands.isEmpty())
                    || (fallingBlocks != null && !fallingBlocks.isEmpty());

                if (!hasAny && !anyLevelActive && itemMergeOn && xpMergeOn) {
                    emptyChunkCache.put(chunkKey, now);
                    continue;
                }
                emptyChunkCache.remove(chunkKey);

                if (items != null) itemMergeTask.mergeInChunk(items);
                if (orbs != null) xpOrbMergeTask.mergeInChunk(orbs);
                if (monsters != null) mobCapEnforcer.enforceInChunk(monsters);
                if (stands != null) armorStandLimiter.enforceInChunk(stands);
                if (fallingBlocks != null) fallingBlockLimiter.enforceInChunk(fallingBlocks);
            }
        }

        if (now - lastCacheSweep > 120000) {
            emptyChunkCache.entrySet().removeIf(e -> now - e.getValue() > CACHE_TTL_MS * 2);
            lastCacheSweep = now;
        }
    }
}
