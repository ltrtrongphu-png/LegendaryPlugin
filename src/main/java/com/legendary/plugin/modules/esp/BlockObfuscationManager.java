package com.legendary.plugin.modules.esp;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.util.CoordCodec;
import com.legendary.plugin.util.RaycastUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-X-ray: rewrites outgoing BLOCK_CHANGE / MULTI_BLOCK_CHANGE packets
 * for "sensitive" ore/valuable materials to look like stone, and only lets
 * the real block through once the viewer is genuinely close enough and has
 * line of sight (a periodic reveal task). Ported from
 * AntiESPUltimate.BlockObfuscationModule, kept ProtocolLib-based since
 * per-packet block-id spoofing has no vanilla Bukkit API equivalent.
 * Entirely optional: if ProtocolLib is absent this manager simply never
 * starts (checked by {@link EspModule}).
 */
public final class BlockObfuscationManager extends PacketAdapter implements Listener {

    private final LegendaryPlugin plugin;
    private final ProtocolManager protocolManager;
    private final Set<Material> obfuscated = EnumSet.noneOf(Material.class);
    private final Map<UUID, Set<Long>> revealedByPlayer = new ConcurrentHashMap<>();
    private WrappedBlockData fakeBlockData;
    private double revealDistance;
    private long revealCheckIntervalTicks;
    private BukkitTask revealTask;

    public BlockObfuscationManager(LegendaryPlugin plugin) {
        super(plugin, ListenerPriority.HIGH,
            PacketType.Play.Server.BLOCK_CHANGE,
            PacketType.Play.Server.MULTI_BLOCK_CHANGE);
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    public void loadConfigValues() {
        obfuscated.clear();
        for (String name : plugin.getConfig().getStringList("esp.block-obfuscation.materials")) {
            try {
                obfuscated.add(Material.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown material in esp.block-obfuscation.materials: " + name);
            }
        }
        // "StorageESP" (a hack-client render feature that highlights chests/barrels/shulkers
        // through walls straight from loaded chunk data, the same way X-ray highlights ore) is
        // defended against the same way as ore: obfuscate the material in outgoing packets
        // until the viewer is genuinely close enough. Off by default since, unlike rare ore,
        // hiding every chest in a base has a bigger everyday-navigation UX cost - opt in per
        // server. Same limitation as ore hiding applies: this only re-obfuscates blocks that
        // CHANGE after a chunk is already loaded (BLOCK_CHANGE/MULTI_BLOCK_CHANGE packets),
        // not the initial chunk stream - see PaperAntiXraySupport and the README for the
        // chunk-load-time gap this plugin cannot close without deeper NMS work.
        if (plugin.getConfig().getBoolean("esp.block-obfuscation.hide-storage-blocks", false)) {
            for (String name : plugin.getConfig().getStringList("esp.block-obfuscation.storage-materials")) {
                try {
                    obfuscated.add(Material.valueOf(name.trim().toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Unknown material in esp.block-obfuscation.storage-materials: " + name);
                }
            }
        }
        revealDistance = ConfigUtil.getBoundedDouble(plugin, "esp.block-obfuscation.reveal-distance", 6.0, 1.0, 32.0);
        revealCheckIntervalTicks = ConfigUtil.getBoundedLong(plugin, "esp.block-obfuscation.reveal-check-interval-ticks", 5, 1, 200);
        fakeBlockData = WrappedBlockData.createData(Material.STONE);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("esp.block-obfuscation.enabled", true)) return;
        protocolManager.addPacketListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        revealTask = Bukkit.getScheduler().runTaskTimer(plugin, this::revealTick, 20L, revealCheckIntervalTicks);
    }

    public void stop() {
        protocolManager.removePacketListener(this);
        org.bukkit.event.HandlerList.unregisterAll(this);
        if (revealTask != null) {
            revealTask.cancel();
            revealTask = null;
        }
        revealedByPlayer.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        revealedByPlayer.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        Player receiver = event.getPlayer();
        if (receiver.hasPermission("legendary.esp.bypass")) return;
        PacketContainer packet = event.getPacket();
        try {
            if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
                BlockPosition pos = packet.getBlockPositionModifier().read(0);
                WrappedBlockData data = packet.getBlockData().read(0);
                if (shouldHide(receiver, pos, data)) {
                    packet.getBlockData().write(0, fakeBlockData);
                }
            } else if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
                // Multi-block-change carries an array of relative positions + block states;
                // ProtocolLib exposes them as MultiBlockChangeInfo[]. NOTE: MultiBlockChangeInfo
                // has getAbsoluteX()/getY()/getAbsoluteZ() - there is no getAbsolutePosition()
                // (that method does not exist on this class; using it would fail to compile).
                var infoArray = packet.getMultiBlockChangeInfoArrays().readSafely(0);
                if (infoArray != null) {
                    for (var info : infoArray) {
                        if (shouldHide(receiver, info.getAbsoluteX(), info.getY(), info.getAbsoluteZ(), info.getData())) {
                            info.setData(fakeBlockData);
                        }
                    }
                    packet.getMultiBlockChangeInfoArrays().write(0, infoArray);
                }
            }
        } catch (Exception ex) {
            plugin.debug("BlockObfuscation packet rewrite failed: " + ex.getMessage());
        }
    }

    private boolean shouldHide(Player receiver, BlockPosition pos, WrappedBlockData data) {
        return shouldHide(receiver, pos.getX(), pos.getY(), pos.getZ(), data);
    }

    private boolean shouldHide(Player receiver, int x, int y, int z, WrappedBlockData data) {
        if (data == null || !obfuscated.contains(data.getType())) return false;
        long key = CoordCodec.pack(x, y, z);
        Set<Long> revealed = revealedByPlayer.get(receiver.getUniqueId());
        return revealed == null || !revealed.contains(key);
    }

    /** Periodically re-checks nearby obfuscated blocks and marks the genuinely visible ones as revealed. */
    private void revealTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("legendary.esp.bypass")) continue;
            Set<Long> revealed = revealedByPlayer.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            Location eye = player.getEyeLocation();
            int radius = (int) Math.ceil(revealDistance);
            int baseX = eye.getBlockX(), baseY = eye.getBlockY(), baseZ = eye.getBlockZ();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int bx = baseX + dx, by = baseY + dy, bz = baseZ + dz;
                        var block = player.getWorld().getBlockAt(bx, by, bz);
                        if (!obfuscated.contains(block.getType())) continue;
                        Location center = block.getLocation().add(0.5, 0.5, 0.5);
                        if (RaycastUtils.canSeeBlock(eye, center, revealDistance)) {
                            long key = CoordCodec.pack(bx, by, bz);
                            if (revealed.add(key)) {
                                resend(player, bx, by, bz);
                            }
                        }
                    }
                }
            }
        }
    }

    private void resend(Player player, int x, int y, int z) {
        try {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
            packet.getBlockPositionModifier().write(0, new BlockPosition(x, y, z));
            packet.getBlockData().write(0, WrappedBlockData.createData(player.getWorld().getBlockAt(x, y, z).getType()));
            protocolManager.sendServerPacket(player, packet);
        } catch (Exception ex) {
            plugin.debug("BlockObfuscation resend failed: " + ex.getMessage());
        }
    }
}
