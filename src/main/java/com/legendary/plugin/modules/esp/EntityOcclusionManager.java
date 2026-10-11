package com.legendary.plugin.modules.esp;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.util.RaycastUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-ESP entity occlusion: intercepts outgoing entity-related packets
 * and cancels them when the target entity is behind solid blocks from
 * the receiver's view.
 *
 * PERFORMANCE UPGRADE: entity ID cache refresh reduced from every tick to
 * every 2 ticks (halves the world.getEntities() iteration cost), and
 * added a distance pre-check before the expensive raycast so most
 * visibility decisions are O(1) distance comparisons instead of world
 * block traces. The visibility sweep now also skips the raycast for
 * entities that are clearly within or clearly outside range.
 */
public final class EntityOcclusionManager extends PacketAdapter implements Listener {

    private final LegendaryPlugin plugin;
    private final ProtocolManager protocolManager;
    private double maxDistance;
    private double maxDistanceSq;
    private double minDistance;
    private double minDistanceSq;
    private double blockingThreshold;
    private boolean enabled;
    private BukkitTask visibilityTask;
    private BukkitTask cacheRefreshTask;
    private final Map<UUID, Set<Integer>> hiddenEntities = new ConcurrentHashMap<>();
    private volatile Map<Integer, Entity> entityIdCache = Map.of();

    public EntityOcclusionManager(LegendaryPlugin plugin) {
        super(plugin, ListenerPriority.HIGH,
            PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.ENTITY_TELEPORT,
            PacketType.Play.Server.REL_ENTITY_MOVE,
            PacketType.Play.Server.REL_ENTITY_MOVE_LOOK);
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("esp.entity-occlusion.enabled", true);
        maxDistance = ConfigUtil.getBoundedDouble(plugin, "esp.entity-occlusion.max-distance", 48.0, 8.0, 256.0);
        maxDistanceSq = maxDistance * maxDistance;
        minDistance = ConfigUtil.getBoundedDouble(plugin, "esp.entity-occlusion.min-distance", 4.0, 0.0, 256.0);
        minDistanceSq = minDistance * minDistance;
        blockingThreshold = ConfigUtil.getBoundedDouble(plugin, "esp.entity-occlusion.blocking-threshold", 0.55, 0.0, 1.0);
    }

    public void start() {
        if (!enabled) return;
        protocolManager.addPacketListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        cacheRefreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshEntityCache, 0L, 2L);
        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateVisibility, 20L, 10L);
    }

    public void stop() {
        protocolManager.removePacketListener(this);
        org.bukkit.event.HandlerList.unregisterAll(this);
        if (visibilityTask != null) { visibilityTask.cancel(); visibilityTask = null; }
        if (cacheRefreshTask != null) { cacheRefreshTask.cancel(); cacheRefreshTask = null; }
        hiddenEntities.clear();
        entityIdCache = Map.of();
    }

    private void refreshEntityCache() {
        Map<Integer, Entity> next = new ConcurrentHashMap<>(512);
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                next.put(entity.getEntityId(), entity);
            }
        }
        entityIdCache = next;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        if (!enabled) return;
        Player receiver = event.getPlayer();
        if (receiver.hasPermission("legendary.esp.bypass")) return;

        int entityId = event.getPacket().getIntegers().readSafely(0);
        if (entityId < 0) return;

        Entity target = entityIdCache.get(entityId);
        if (target == null || target instanceof Player) return;

        if (shouldHideEntity(receiver, target)) {
            event.setCancelled(true);
            hiddenEntities.computeIfAbsent(receiver.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(entityId);
        } else {
            Set<Integer> hidden = hiddenEntities.get(receiver.getUniqueId());
            if (hidden != null) hidden.remove(entityId);
        }
    }

    private boolean shouldHideEntity(Player viewer, Entity target) {
        double distSq = viewer.getLocation().distanceSquared(target.getLocation());
        if (distSq <= minDistanceSq) return false;
        if (distSq > maxDistanceSq) return true;
        return !RaycastUtils.hasLineOfSight(
            viewer.getEyeLocation(), target.getLocation().add(0, target.getHeight() / 2, 0), blockingThreshold);
    }

    private void updateVisibility() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.hasPermission("legendary.esp.bypass")) continue;
            Set<Integer> hidden = hiddenEntities.get(viewer.getUniqueId());
            if (hidden == null) continue;
            hidden.removeIf(id -> {
                Entity e = entityIdCache.get(id);
                return e == null || !shouldHideEntity(viewer, e);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hiddenEntities.remove(event.getPlayer().getUniqueId());
    }
}
