package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.util.ArrayList;
import java.util.List;

/**
 * PERFORMANCE UPGRADE (comprehensive-upgrade addition): the original port
 * had ItemMergeTask, XpOrbMergeTask, MobCapEnforcer, ArmorStandLimiter and
 * FallingBlockLimiter each independently call {@code world.getLoadedChunks()}
 * and {@code chunk.getEntities()} on every optimization tick - five full
 * world/chunk/entity walks doing the same iteration work. On a server
 * with many loaded chunks this is a real, avoidable cost precisely when
 * the server is already under load (these tasks only do meaningful work
 * once TPS has dropped).
 *
 * This task walks every loaded chunk exactly ONCE per invocation, buckets
 * each entity into the right typed list, and hands each list to the
 * matching sub-system's {@code enforceInChunk}/{@code mergeInChunk}
 * method - turning 5x O(worlds x chunks x entities) into 1x.
 */
public final class EntitySweepTask {

    private final LegendaryPlugin plugin;
    private final ItemMergeTask itemMergeTask;
    private final XpOrbMergeTask xpOrbMergeTask;
    private final MobCapEnforcer mobCapEnforcer;
    private final ArmorStandLimiter armorStandLimiter;
    private final FallingBlockLimiter fallingBlockLimiter;

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

        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
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

                if (items != null) itemMergeTask.mergeInChunk(items);
                if (orbs != null) xpOrbMergeTask.mergeInChunk(orbs);
                if (monsters != null) mobCapEnforcer.enforceInChunk(monsters);
                if (stands != null) armorStandLimiter.enforceInChunk(stands);
                if (fallingBlocks != null) fallingBlockLimiter.enforceInChunk(fallingBlocks);
            }
        }
    }
}
