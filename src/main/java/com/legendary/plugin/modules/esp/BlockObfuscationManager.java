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
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    private final Map<UUID, LinkedHashMap<Long, Boolean>> revealedByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastRevealLocations = new ConcurrentHashMap<>();
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
        revealDistance = ConfigUtil.getBoundedDouble(plugin, "esp.block-obfuscation.reveal-distance", 6.0, 1.0, 8.0);
        revealCheckIntervalTicks = ConfigUtil.getBoundedLong(plugin, "esp.block-obfuscation.reveal-check-interval-ticks", 5, 1, 200);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("esp.block-obfuscation.enabled", false)) return;
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
        lastRevealLocations.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        revealedByPlayer.remove(event.getPlayer().getUniqueId());
        lastRevealLocations.remove(event.getPlayer().getUniqueId());
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
                    packet.getBlockData().write(0, fakeBlockData(receiver, pos.getY()));
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
                            info.setData(fakeBlockData(receiver, info.getY()));
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
        LinkedHashMap<Long, Boolean> revealed = revealedByPlayer.get(receiver.getUniqueId());
        if (revealed == null) return true;
        synchronized (revealed) {
            return !revealed.containsKey(key);
        }
    }

    private WrappedBlockData fakeBlockData(Player player, int y) {
        Material replacement = switch (player.getWorld().getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> y <= 0 ? Material.DEEPSLATE : Material.STONE;
        };
        return WrappedBlockData.createData(replacement);
    }

    /** Periodically re-checks nearby obfuscated blocks and marks the genuinely visible ones as revealed. */
    private void revealTick() {
        double revealDistSq = revealDistance * revealDistance;
        long deadline = System.nanoTime() + 1_000_000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (System.nanoTime() >= deadline) return;
            if (player.hasPermission("legendary.esp.bypass")) continue;
            Location eye = player.getEyeLocation();
            Location last = lastRevealLocations.get(player.getUniqueId());
            if (last != null && last.getWorld().equals(eye.getWorld()) && last.distanceSquared(eye) < 0.25) continue;
            lastRevealLocations.put(player.getUniqueId(), eye.clone());
            LinkedHashMap<Long, Boolean> revealed =
                revealedByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashMap<>(256, 0.75f, true));
            synchronized (revealed) {
                revealed.entrySet().removeIf(entry -> {
                    long key = entry.getKey();
                    double dx = CoordCodec.unpackX(key) - eye.getX();
                    double dy = CoordCodec.unpackY(key) - eye.getY();
                    double dz = CoordCodec.unpackZ(key) - eye.getZ();
                    return dx * dx + dy * dy + dz * dz > revealDistSq * 4;
                });
            }
            int radius = (int) Math.ceil(revealDistance);
            int baseX = eye.getBlockX(), baseY = eye.getBlockY(), baseZ = eye.getBlockZ();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (System.nanoTime() >= deadline) return;
                        int bx = baseX + dx, by = baseY + dy, bz = baseZ + dz;
                        if (dx * dx + dy * dy + dz * dz > revealDistSq) continue;
                        var block = player.getWorld().getBlockAt(bx, by, bz);
                        if (!obfuscated.contains(block.getType())) continue;
                        Location center = block.getLocation().add(0.5, 0.5, 0.5);
                        if (RaycastUtils.canSeeBlock(eye, center, revealDistance)) {
                            long key = CoordCodec.pack(bx, by, bz);
                            boolean wasAdded;
                            synchronized (revealed) {
                                wasAdded = !revealed.containsKey(key);
                                revealed.put(key, Boolean.TRUE);
                                while (revealed.size() > 4096) {
                                    Iterator<Long> oldest = revealed.keySet().iterator();
                                    oldest.next();
                                    oldest.remove();
                                }
                            }
                            if (wasAdded) {
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
