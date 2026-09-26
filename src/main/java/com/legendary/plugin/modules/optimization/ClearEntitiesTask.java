package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.Set;

/**
 * Scheduled (and manually triggerable) mass entity clear, with a
 * broadcast warning beforehand. Ported from SmartOptimizer.ClearEntitiesTask.
 *
 * Two independent triggers:
 *   - {@link #runScheduled()} - fires only while the server is under SEVERE
 *     lag (called every optimization tick from OptimizationModule).
 *   - {@link #startPeriodicSchedule} - a fixed-interval clear regardless of
 *     TPS (e.g. every 5 minutes), for servers that just want a steady
 *     "sweep the floor" independent of lag state. Added 2026-09-19 per
 *     user request.
 */
public final class ClearEntitiesTask {

    public enum ClearType { ITEMS, MOBS, BOTH }

    private final LegendaryPlugin plugin;
    private volatile OptimizationEngine.Level currentLevel = OptimizationEngine.Level.NORMAL;
    private BukkitTask periodicTask;

    public ClearEntitiesTask(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateLevel(OptimizationEngine.Level level) {
        this.currentLevel = level;
    }

    public void runScheduled() {
        if (currentLevel == OptimizationEngine.Level.SEVERE) {
            broadcastWarning(10);
            Bukkit.getScheduler().runTaskLater(plugin, () -> executeClear(null), 200L);
        }
    }

    /** Starts a fixed-interval clear independent of TPS/lag level. Call once from onEnable(). */
    public void startPeriodicSchedule(long intervalSeconds, int warnSecondsBefore) {
        stopPeriodicSchedule();
        long intervalTicks = Math.max(20L, intervalSeconds * 20L);
        long warnTicks = Math.max(20L, warnSecondsBefore * 20L);
        periodicTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (warnSecondsBefore > 0) {
                broadcastWarning(warnSecondsBefore);
                Bukkit.getScheduler().runTaskLater(plugin, () -> executeClear(null), warnTicks);
            } else {
                executeClear(null);
            }
        }, intervalTicks, intervalTicks);
    }

    public void stopPeriodicSchedule() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
    }

    public void runNow(CommandSender trigger) {
        executeClear(trigger);
    }

    private void broadcastWarning(int seconds) {
        for (var player : Bukkit.getOnlinePlayers()) {
            Text.send(player, "<yellow>[LegendaryPlugin] <white>Clearing loose items/mobs in " + seconds + "s.");
        }
    }

    private void executeClear(CommandSender trigger) {
        // PERFORMANCE FIX (2026-09-20, per user report of commands causing lag): the previous
        // version iterated every loaded chunk in every world synchronously, all in one call.
        // With auto-clear now running on a fixed timer (every 5 min by default), that meant a
        // periodic full-server chunk scan in a single tick - spread across several ticks
        // instead (40 chunks/tick), same as ChunkHealthReporter's fix for /legendary topchunks.
        java.util.List<Chunk> allChunks = new java.util.ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            allChunks.addAll(java.util.Arrays.asList(world.getLoadedChunks()));
        }
        if (allChunks.isEmpty()) {
            if (trigger != null) Text.send(trigger, "<green>Cleared <white>0<green> entities.");
            return;
        }

        Set<ClearType> types = EnumSet.of(ClearType.ITEMS, ClearType.MOBS);
        int[] cleared = {0};
        int[] index = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int processed = 0;
            while (index[0] < allChunks.size() && processed < 40) {
                cleared[0] += clearInChunk(allChunks.get(index[0]), types);
                index[0]++;
                processed++;
            }
            if (index[0] >= allChunks.size()) {
                task.cancel();
                if (trigger != null) {
                    Text.send(trigger, "<green>Cleared <white>" + cleared[0] + "<green> entities.");
                }
                plugin.getLogger().info("[Optimization] Cleared " + cleared[0] + " entities (level=" + currentLevel + ")");
            }
        }, 0L, 1L);
    }

    private int clearInChunk(Chunk chunk, Set<ClearType> types) {
        int count = 0;
        for (Entity entity : chunk.getEntities()) {
            if (matchesAndAllowed(entity, types)) {
                entity.remove();
                count++;
            }
        }
        return count;
    }

    private boolean matchesAndAllowed(Entity e, Set<ClearType> types) {
        if (e instanceof Item item && types.contains(ClearType.ITEMS)) {
            return !ProtectionRules.isProtectedItem(item, true);
        }
        if (e instanceof Monster monster && types.contains(ClearType.MOBS)) {
            return !ProtectionRules.isProtectedMob((LivingEntity) monster);
        }
        return false;
    }
}
