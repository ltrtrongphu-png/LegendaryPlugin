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
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts a narrow set of client packets on Netty; threshold checks run on the server thread. */
public final class PacketFloodCheck extends PacketAdapter {

    private static final Set<PacketType> OBSERVED_PACKETS = Set.of(
        PacketType.Play.Client.POSITION, PacketType.Play.Client.POSITION_LOOK,
        PacketType.Play.Client.LOOK, PacketType.Play.Client.FLYING,
        PacketType.Play.Client.USE_ENTITY, PacketType.Play.Client.ARM_ANIMATION,
        PacketType.Play.Client.BLOCK_DIG, PacketType.Play.Client.BLOCK_PLACE,
        PacketType.Play.Client.WINDOW_CLICK);
    private final AnticheatModule anticheat;
    private final Map<UUID, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> bypassCache = new ConcurrentHashMap<>();
    private volatile int maxPerSecond = 400;
    private BukkitTask evaluationTask;

    public PacketFloodCheck(LegendaryPlugin plugin, AnticheatModule anticheat) {
        super(plugin, ListenerPriority.NORMAL, OBSERVED_PACKETS);
        this.anticheat = anticheat;
    }

    public void loadConfigValues() {
        maxPerSecond = Math.max(20,
            anticheat.getPlugin().getConfig().getInt("anticheat.checks.packetflood.max-per-second", 400));
    }

    public void register() {
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        manager.addPacketListener(this);
        evaluationTask = anticheat.getPlugin().getServer().getScheduler().runTaskTimer(
            anticheat.getPlugin(), this::evaluateWindow, 20L, 20L);
    }

    public void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
        if (evaluationTask != null) { evaluationTask.cancel(); evaluationTask = null; }
        counters.clear();
        bypassCache.clear();
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        if (!Boolean.TRUE.equals(bypassCache.get(uuid))) {
            counters.computeIfAbsent(uuid, ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    private void evaluateWindow() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            boolean bypass = anticheat.getBypassGuard().shouldBypass(player, "packetflood");
            bypassCache.put(uuid, bypass);
            AtomicInteger counter = counters.get(uuid);
            int count = counter == null ? 0 : counter.getAndSet(0);
            if (!bypass && count > maxPerSecond) {
                anticheat.flagRaw(player, "packetflood", 1.0, "packets/s=" + count);
            }
        }
        counters.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        bypassCache.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }
}
