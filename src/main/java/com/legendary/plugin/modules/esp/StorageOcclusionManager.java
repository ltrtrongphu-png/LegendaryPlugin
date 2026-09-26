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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Anti-ESP for StorageESP: intercepts outgoing BLOCK_ENTITY_DATA packets
 * (TileEntityData) that carry chest/shulker/sign/spawner metadata. Hack
 * clients use these packets to highlight containers through walls. This
 * manager cancels the packet if the block is beyond a safe distance and
 * has no line-of-sight from the receiver, preventing the client from ever
 * learning the container exists until the player is genuinely close enough
 * to see it.
 *
 * Only active when ProtocolLib is present.
 */
public final class StorageOcclusionManager extends PacketAdapter implements Listener {

    private final LegendaryPlugin plugin;
    private final ProtocolManager protocolManager;
    private final Set<Material> protectedContainers = EnumSet.noneOf(Material.class);
    private double revealDistance;
    private boolean enabled;

    public StorageOcclusionManager(LegendaryPlugin plugin) {
        super(plugin, ListenerPriority.HIGH,
            PacketType.Play.Server.TILE_ENTITY_DATA);
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("esp.storage-occlusion.enabled", true);
        revealDistance = ConfigUtil.getBoundedDouble(plugin, "esp.storage-occlusion.reveal-distance", 5.0, 1.0, 32.0);
        protectedContainers.clear();
        for (String name : plugin.getConfig().getStringList("esp.storage-occlusion.materials")) {
            try {
                protectedContainers.add(Material.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown material in esp.storage-occlusion.materials: " + name);
            }
        }
        if (protectedContainers.isEmpty()) {
            for (String name : new String[]{
                "CHEST", "ENDER_CHEST", "TRAPPED_CHEST",
                "SHULKER_BOX", "WHITE_SHULKER_BOX", "ORANGE_SHULKER_BOX",
                "MAGENTA_SHULKER_BOX", "LIGHT_BLUE_SHULKER_BOX", "YELLOW_SHULKER_BOX",
                "LIME_SHULKER_BOX", "PINK_SHULKER_BOX", "GRAY_SHULKER_BOX",
                "CYAN_SHULKER_BOX", "PURPLE_SHULKER_BOX", "BLUE_SHULKER_BOX",
                "BROWN_SHULKER_BOX", "GREEN_SHULKER_BOX", "RED_SHULKER_BOX",
                "BLACK_SHULKER_BOX", "BARREL"
            }) {
                try {
                    protectedContainers.add(Material.valueOf(name));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void start() {
        if (!enabled) return;
        protocolManager.addPacketListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void stop() {
        protocolManager.removePacketListener(this);
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        if (!enabled) return;
        Player receiver = event.getPlayer();
        if (receiver.hasPermission("legendary.esp.bypass")) return;

        try {
            var pos = event.getPacket().getBlockPositionModifier().readSafely(0);
            if (pos == null) return;
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            Block block = receiver.getWorld().getBlockAt(x, y, z);
            if (!protectedContainers.contains(block.getType())) return;

            Location eye = receiver.getEyeLocation();
            Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
            if (!eye.getWorld().equals(blockCenter.getWorld())) { event.setCancelled(true); return; }
            double distSq = eye.distanceSquared(blockCenter);
            if (distSq <= revealDistance * revealDistance) return;

            if (!RaycastUtils.canSeeBlock(eye, blockCenter, revealDistance + 2)) {
                event.setCancelled(true);
            }
        } catch (Exception ex) {
            plugin.debug("StorageOcclusion packet check failed: " + ex.getMessage());
        }
    }

}
