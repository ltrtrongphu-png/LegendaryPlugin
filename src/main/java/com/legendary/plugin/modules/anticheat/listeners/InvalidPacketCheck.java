package com.legendary.plugin.modules.anticheat.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Validates packet fields on Netty and schedules Bukkit-dependent actions on the server thread. */
public final class InvalidPacketCheck extends PacketAdapter {

    private final LegendaryPlugin plugin;
    private final AnticheatModule anticheat;
    private final Map<UUID, AtomicInteger> invalidCounts = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastDetails = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> bypassCache = new ConcurrentHashMap<>();
    private BukkitTask evaluationTask;

    public InvalidPacketCheck(LegendaryPlugin plugin, AnticheatModule anticheat) {
        super(plugin, ListenerPriority.NORMAL,
            PacketType.Play.Client.POSITION,
            PacketType.Play.Client.POSITION_LOOK,
            PacketType.Play.Client.LOOK);
        this.plugin = plugin;
        this.anticheat = anticheat;
    }

    public void register() {
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        manager.addPacketListener(this);
        evaluationTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::evaluate, 20L, 20L);
    }

    public void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
        if (evaluationTask != null) { evaluationTask.cancel(); evaluationTask = null; }
        invalidCounts.clear();
        lastDetails.clear();
        bypassCache.clear();
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        var player = event.getPlayer();
        if (player == null || Boolean.TRUE.equals(bypassCache.get(player.getUniqueId()))) return;
        try {
            var packet = event.getPacket();
            for (double coordinate : packet.getDoubles().getValues()) {
                if (Double.isNaN(coordinate) || Double.isInfinite(coordinate) || Math.abs(coordinate) > 3.0E7) {
                    recordInvalid(player.getUniqueId(), "bad coordinate=" + coordinate);
                    event.setCancelled(true);
                    return;
                }
            }
            for (float angle : packet.getFloat().getValues()) {
                if (Float.isNaN(angle) || Float.isInfinite(angle)) {
                    recordInvalid(player.getUniqueId(), "bad angle=" + angle);
                    event.setCancelled(true);
                    return;
                }
            }
        } catch (RuntimeException exception) {
            plugin.debug("InvalidPacketCheck failed to read packet: " + exception.getMessage());
        }
    }

    private void recordInvalid(UUID uuid, String detail) {
        invalidCounts.computeIfAbsent(uuid, ignored -> new AtomicInteger()).incrementAndGet();
        lastDetails.put(uuid, detail);
    }

    private void evaluate() {
        for (var player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            boolean bypass = anticheat.getBypassGuard().shouldBypass(player, "invalidpacket");
            bypassCache.put(uuid, bypass);
            AtomicInteger count = invalidCounts.get(uuid);
            int total = count == null ? 0 : count.getAndSet(0);
            if (!bypass && total > 0) {
                anticheat.flagRaw(player, "invalidpacket", Math.min(4.0, total * 2.0),
                    lastDetails.getOrDefault(uuid, "invalid packet"));
            }
        }
        invalidCounts.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        lastDetails.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        bypassCache.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }
}
