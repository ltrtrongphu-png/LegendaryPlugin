package com.legendary.plugin.modules.anticheat.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Flags a player whose client is flooding the server with far more
 * packets per second than any real client would produce (packet-flood /
 * network-exploit style attacks). Requires ProtocolLib. Ported from
 * BaB.PacketFloodCheck.
 */
public final class PacketFloodCheck extends PacketAdapter {

    private final AnticheatModule anticheat;
    private final Map<UUID, AtomicInteger> counters = new ConcurrentHashMap<>();
    private volatile int maxPerSecond = 400;
    private org.bukkit.scheduler.BukkitTask resetTask;

    public PacketFloodCheck(LegendaryPlugin plugin, AnticheatModule anticheat) {
        // ✅ FIXED: getInstance() trước khi gọi values()
        super(plugin, ListenerPriority.MONITOR, PacketType.Play.Client.getInstance().values());
        this.anticheat = anticheat;
    }

    public void loadConfigValues() {
        maxPerSecond = anticheat.getPlugin().getConfig().getInt("anticheat.checks.packetflood.max-per-second", 400);
    }

    public void register() {
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        manager.addPacketListener(this);
        resetTask = anticheat.getPlugin().getServer().getScheduler().runTaskTimer(
            anticheat.getPlugin(), counters::clear, 20L, 20L);
    }

    public void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
        if (resetTask != null) { resetTask.cancel(); resetTask = null; }
        counters.clear();
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null || anticheat.getBypassGuard().shouldBypass(player, "packetflood")) return;
        int count = counters.computeIfAbsent(player.getUniqueId(), k -> new AtomicInteger()).incrementAndGet();
        if (count >= maxPerSecond + 1) { // fire when over the limit
            anticheat.flagRaw(player, "packetflood", 1.0, "packets/s=" + count);
        }
    }
}
