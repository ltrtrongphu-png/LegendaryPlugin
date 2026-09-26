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

/**
 * Anti-ESP entity occlusion: intercepts outgoing entity-related packets
 * (SPAWN_ENTITY, ENTITY_TELEPORT, REL_ENTITY_MOVE) and cancels them when
 * the target entity is behind solid blocks from the receiver's view,
 * defeating Player/Mob ESP hacks that render entities through walls.
 *
 * Uses ProtocolLib packet-level interception (the only way to prevent the
 * client from ever receiving the entity data) combined with ray-cast
 * occlusion culling from the receiver's eye location to the entity's
 * bounding box. Only active when ProtocolLib is present.
 */
public final class EntityOcclusionManager extends PacketAdapter implements Listener {

    private final LegendaryPlugin plugin;
    private final ProtocolManager protocolManager;
    private double maxDistance;
    private double minDistance;
    private double blockingThreshold;
    private boolean enabled;
    private BukkitTask visibilityTask;
    private final Map<UUID, Set<Integer>> hiddenEntities = new ConcurrentHashMap<>();

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
        minDistance = ConfigUtil.getBoundedDouble(plugin, "esp.entity-occlusion.min-distance", 4.0, 0.0, 256.0);
        blockingThreshold = ConfigUtil.getBoundedDouble(plugin, "esp.entity-occlusion.blocking-threshold", 0.55, 0.0, 1.0);
    }

    public void start() {
        if (!enabled) return;
        protocolManager.addPacketListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateVisibility, 20L, 10L);
    }

    public void stop() {
        protocolManager.removePacketListener(this);
        org.bukkit.event.HandlerList.unregisterAll(this);
        if (visibilityTask != null) {
            visibilityTask.cancel();
            visibilityTask = null;
        }
        hiddenEntities.clear();
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        if (!enabled) return;
        Player receiver = event.getPlayer();
        if (receiver.hasPermission("legendary.esp.bypass")) return;

        int entityId = event.getPacket().getIntegers().readSafely(0);
        if (entityId < 0) return;

        Entity target = getEntityById(entityId);
        if (target == null || target instanceof Player) return;

        if (shouldHideEntity(receiver, target)) {
            event.setCancelled(true);
            hiddenEntities.computeIfAbsent(receiver.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(entityId);
        } else {
            Set<Integer> hidden = hiddenEntities.get(receiver.getUniqueId());
            if (hidden != null) {
                hidden.remove(entityId);
            }
        }
    }

    private boolean shouldHideEntity(Player viewer, Entity target) {
        if (!viewer.getWorld().equals(target.getWorld())) return true;
        double distSq = viewer.getLocation().distanceSquared(target.getLocation());
        if (distSq <= minDistance * minDistance) return false;
        if (distSq > maxDistance * maxDistance) return true;
        return !RaycastUtils.hasLineOfSight(
            viewer.getEyeLocation(), target.getLocation().add(0, target.getHeight() / 2, 0), blockingThreshold);
    }

    private void updateVisibility() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.hasPermission("legendary.esp.bypass")) continue;
            Set<Integer> hidden = hiddenEntities.get(viewer.getUniqueId());
            if (hidden == null) continue;
            hidden.removeIf(id -> {
                Entity e = getEntityById(id);
                return e == null || !shouldHideEntity(viewer, e);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hiddenEntities.remove(event.getPlayer().getUniqueId());
    }

    private Entity getEntityById(int entityId) {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getEntityId() == entityId) return entity;
            }
        }
        return null;
    }
}
