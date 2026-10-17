package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
public final class ClearEntitiesTask implements Listener {

    public enum ClearType { ITEMS, MOBS, BOTH }

    private final LegendaryPlugin plugin;
    private volatile OptimizationEngine.Level currentLevel = OptimizationEngine.Level.NORMAL;
    private final AtomicBoolean running = new AtomicBoolean();
    private BukkitTask periodicTask;
    private final long minSecondsBetweenClears;
    private final int minItemAgeTicks;
    private final boolean clearValuables;
    private final Set<String> allowlistedChunks;
    private final NamespacedKey playerDeathDropKey;
    private volatile long lastClearAt;

    public ClearEntitiesTask(LegendaryPlugin plugin) {
        this.plugin = plugin;
        minSecondsBetweenClears = Math.max(0L,
            plugin.getConfig().getLong("optimization.auto-clear.min-seconds-between-clears", 300));
        minItemAgeTicks = (int) Math.min(Integer.MAX_VALUE,
            Math.max(0L, plugin.getConfig().getLong("optimization.auto-clear.min-item-age-seconds", 120)) * 20L);
        clearValuables = plugin.getConfig().getBoolean("optimization.auto-clear.clear-valuables", false);
        allowlistedChunks = new HashSet<>(plugin.getConfig().getStringList("optimization.auto-clear.allowlisted-chunks"));
        playerDeathDropKey = new NamespacedKey(plugin, "player-death-drop");
    }

    public void updateLevel(OptimizationEngine.Level level) {
        this.currentLevel = level;
    }

    public void runScheduled() {
        if (currentLevel == OptimizationEngine.Level.SEVERE && reserveClear()) {
            broadcastWarning(10);
            Bukkit.getScheduler().runTaskLater(plugin, () -> executeReservedClear(null), 200L);
        }
    }

    /** Starts a fixed-interval clear independent of TPS/lag level. Call once from onEnable(). */
    public void startPeriodicSchedule(long intervalSeconds, int warnSecondsBefore) {
        stopPeriodicSchedule();
        long intervalTicks = Math.max(20L, intervalSeconds * 20L);
        long warnTicks = Math.max(20L, warnSecondsBefore * 20L);
        periodicTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!reserveClear()) return;
            if (warnSecondsBefore > 0) {
                broadcastWarning(warnSecondsBefore);
                Bukkit.getScheduler().runTaskLater(plugin, () -> executeReservedClear(null), warnTicks);
            } else {
                executeReservedClear(null);
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
        if (reserveClear()) executeReservedClear(trigger);
    }

    private void broadcastWarning(int seconds) {
        for (var player : Bukkit.getOnlinePlayers()) {
            Text.send(player, "<yellow>[LegendaryPlugin] <white>Clearing loose items/mobs in " + seconds + "s.");
        }
    }

    private boolean reserveClear() {
        if (!running.compareAndSet(false, true)) return false;
        long now = System.currentTimeMillis();
        if (now - lastClearAt < minSecondsBetweenClears * 1000L) {
            running.set(false);
            return false;
        }
        lastClearAt = now;
        return true;
    }

    private void executeReservedClear(CommandSender trigger) {
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
            running.set(false);
            return;
        }

        Set<ClearType> types = EnumSet.of(ClearType.ITEMS, ClearType.MOBS);
        int[] cleared = {0};
        int[] index = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int processed = 0;
            while (index[0] < allChunks.size() && processed < 40) {
                Chunk chunk = allChunks.get(index[0]);
                if (chunk.getWorld().isChunkLoaded(chunk.getX(), chunk.getZ())) {
                    cleared[0] += clearInChunk(chunk, types);
                }
                index[0]++;
                processed++;
            }
            if (index[0] >= allChunks.size()) {
                task.cancel();
                if (trigger != null) {
                    Text.send(trigger, "<green>Cleared <white>" + cleared[0] + "<green> entities.");
                }
                plugin.getLogger().info("[Optimization] Cleared " + cleared[0] + " entities (level=" + currentLevel + ")");
                plugin.getMetrics().counter("optimization.entities.removed").add(cleared[0]);
                running.set(false);
            }
        }, 0L, 1L);
    }

    private int clearInChunk(Chunk chunk, Set<ClearType> types) {
        if (allowlistedChunks.contains(chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ())) return 0;
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
            return !ProtectionRules.isProtectedItem(item, true, minItemAgeTicks, clearValuables);
        }
        if (e instanceof Monster monster && types.contains(ClearType.MOBS)) {
            return !ProtectionRules.isProtectedMob((LivingEntity) monster);
        }
        return false;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        for (ItemStack drop : event.getDrops()) {
            var meta = drop.getItemMeta();
            if (meta == null) continue;
            meta.getPersistentDataContainer().set(playerDeathDropKey, PersistentDataType.BYTE, (byte) 1);
            drop.setItemMeta(meta);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobDamagedByPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Monster monster && event.getEntity() instanceof Player) {
            ProtectionRules.recordMobCombat(monster);
        } else if (event.getEntity() instanceof Monster monster && event.getDamager() instanceof Player) {
            ProtectionRules.recordMobCombat(monster);
        }
    }

    @EventHandler
    public void onMobTargetPlayer(EntityTargetLivingEntityEvent event) {
        if (event.getEntity() instanceof Monster monster && event.getTarget() instanceof Player) {
            ProtectionRules.recordMobCombat(monster);
        }
    }
}
