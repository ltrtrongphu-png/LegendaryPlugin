package com.legendary.plugin.modules.esp;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.util.RaycastUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Hides occluded entities using Paper's supported per-plugin visibility API. */
public final class EntityOcclusionManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Set<UUID>> hiddenByViewer = new HashMap<>();
    private final Map<UUID, Map<UUID, Integer>> visibleStreaks = new HashMap<>();
    private Set<String> allowlistedTypes = Set.of();
    private double maxDistance;
    private double minDistance;
    private double blockingThreshold;
    private long budgetNanos;
    private int viewerCursor;
    private BukkitTask visibilityTask;

    public EntityOcclusionManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        maxDistance = ConfigUtil.getBoundedDouble(plugin, "esp.entity-occlusion.max-distance", 48.0, 8.0, 256.0);
        minDistance = ConfigUtil.getBoundedDouble(plugin, "esp.entity-occlusion.min-distance", 4.0, 0.0, maxDistance);
        blockingThreshold = ConfigUtil.getBoundedDouble(plugin, "esp.entity-occlusion.blocking-threshold", 0.55, 0.0, 1.0);
        budgetNanos = 1_000_000L;
        allowlistedTypes = Set.copyOf(plugin.getConfig().getStringList("esp.entity-occlusion.entity-type-allowlist")
            .stream().map(String::toUpperCase).toList());
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("esp.entity-occlusion.enabled", true)) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateVisibility, 1L, 1L);
    }

    public void stop() {
        if (visibilityTask != null) {
            visibilityTask.cancel();
            visibilityTask = null;
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
        for (Map.Entry<UUID, Set<UUID>> entry : hiddenByViewer.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null) continue;
            for (UUID entityId : entry.getValue()) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) viewer.showEntity(plugin, entity);
            }
        }
        hiddenByViewer.clear();
        visibleStreaks.clear();
    }

    private void updateVisibility() {
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            viewerCursor = 0;
            return;
        }
        if (viewerCursor >= players.size()) viewerCursor = 0;
        Player viewer = players.get(viewerCursor++);
        if (viewer.hasPermission("legendary.esp.bypass")) {
            showHiddenEntities(viewer);
            return;
        }
        long deadline = System.nanoTime() + budgetNanos;
        Set<UUID> candidates = new HashSet<>();
        Set<UUID> hidden = hiddenByViewer.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>());
        Map<UUID, Integer> streaks = visibleStreaks.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashMap<>());

        List<Entity> nearby = viewer.getNearbyEntities(maxDistance, maxDistance, maxDistance);
        for (Entity target : nearby) {
            if (System.nanoTime() >= deadline) return;
            UUID targetId = target.getUniqueId();
            if (isExempt(target)) {
                if (hidden.remove(targetId)) viewer.showEntity(plugin, target);
                streaks.remove(targetId);
                continue;
            }
            candidates.add(targetId);
            boolean shouldHide = shouldHide(viewer, target);
            if (shouldHide) {
                streaks.remove(targetId);
                if (hidden.add(targetId)) viewer.hideEntity(plugin, target);
            } else if (hidden.contains(targetId)) {
                int clearCount = streaks.merge(targetId, 1, Integer::sum);
                if (clearCount >= 2) {
                    viewer.showEntity(plugin, target);
                    hidden.remove(targetId);
                    streaks.remove(targetId);
                }
            }
        }

        for (UUID hiddenId : Set.copyOf(hidden)) {
            if (System.nanoTime() >= deadline) break;
            if (candidates.contains(hiddenId)) continue;
            Entity target = Bukkit.getEntity(hiddenId);
            if (target == null || !target.isValid()) {
                if (target != null) viewer.showEntity(plugin, target);
                hidden.remove(hiddenId);
                streaks.remove(hiddenId);
            } else if (shouldHide(viewer, target)) {
                streaks.remove(hiddenId);
            } else if (streaks.merge(hiddenId, 1, Integer::sum) >= 2) {
                viewer.showEntity(plugin, target);
                hidden.remove(hiddenId);
                streaks.remove(hiddenId);
            }
        }

    }

    private void showHiddenEntities(Player viewer) {
        Set<UUID> hidden = hiddenByViewer.remove(viewer.getUniqueId());
        visibleStreaks.remove(viewer.getUniqueId());
        if (hidden == null) return;
        for (UUID targetId : hidden) {
            Entity target = Bukkit.getEntity(targetId);
            if (target != null) viewer.showEntity(plugin, target);
        }
    }

    private boolean isExempt(Entity entity) {
        if (entity instanceof Player || entity instanceof Boss || entity.isGlowing()
            || allowlistedTypes.contains(entity.getType().name())
            || !entity.getPersistentDataContainer().isEmpty()
            || entity.getPassengers().stream().anyMatch(Player.class::isInstance)) return true;
        if (entity instanceof LivingEntity living) {
            try {
                if (living.isLeashed()) return true;
            } catch (IllegalStateException ignored) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldHide(Player viewer, Entity target) {
        double distanceSquared = viewer.getLocation().distanceSquared(target.getLocation());
        if (distanceSquared <= minDistance * minDistance
            || distanceSquared > maxDistance * maxDistance) return false;
        return !RaycastUtils.hasLineOfSight(viewer.getEyeLocation(),
            target.getLocation().add(0, target.getHeight() / 2, 0), blockingThreshold);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Set<UUID> hidden = hiddenByViewer.remove(playerId);
        visibleStreaks.remove(playerId);
        if (hidden == null) return;
        for (Set<UUID> otherHidden : hiddenByViewer.values()) otherHidden.remove(playerId);
        for (Map<UUID, Integer> streaks : visibleStreaks.values()) streaks.remove(playerId);
    }
}
