package com.legendary.plugin.modules.anticheat.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class DisablerCheck extends PacketAdapter {

    private final LegendaryPlugin plugin;
    private final AnticheatModule anticheat;
    private final ProtocolManager protocolManager;
    private final Map<UUID, Short> pendingTransactionId = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingTransactionSentAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> missedTransactions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> wrongIdTransactions = new ConcurrentHashMap<>();
    private final AtomicInteger nextTransactionId = new AtomicInteger(1);
    private BukkitTask probeTask;
    private BukkitTask timeoutTask;
    private boolean enabled;
    private long probeIntervalTicks;
    private long rttThresholdMs;
    private int requiredMissedToFlag;
    private int requiredWrongIdToFlag;

    public DisablerCheck(LegendaryPlugin plugin, AnticheatModule anticheat) {
        super(plugin, ListenerPriority.NORMAL,
            PacketType.Play.Client.PONG);
        this.plugin = plugin;
        this.anticheat = anticheat;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("anticheat.checks.disabler.enabled", true);
        probeIntervalTicks = plugin.getConfig().getLong("anticheat.checks.disabler.probe-interval-ticks", 100L);
        rttThresholdMs = plugin.getConfig().getLong("anticheat.checks.disabler.rtt-threshold-ms", 3000);
        requiredMissedToFlag = plugin.getConfig().getInt("anticheat.checks.disabler.required-missed", 3);
        requiredWrongIdToFlag = plugin.getConfig().getInt("anticheat.checks.disabler.required-wrong-id", 2);
    }

    public void register() {
        if (!enabled) return;
        if (protocolManager == null) return;
        protocolManager.addPacketListener(this);
        probeTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sendProbes, 20L, probeIntervalTicks);
        timeoutTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkTimeouts, 40L, 20L);
    }

    public void unregister() {
        if (protocolManager != null) protocolManager.removePacketListener(this);
        if (probeTask != null) { probeTask.cancel(); probeTask = null; }
        if (timeoutTask != null) { timeoutTask.cancel(); timeoutTask = null; }
        pendingTransactionId.clear();
        pendingTransactionSentAt.clear();
        missedTransactions.clear();
        wrongIdTransactions.clear();
    }

    private void sendProbes() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (anticheat.getBypassGuard().shouldBypass(player, "disabler")) continue;
            UUID uuid = player.getUniqueId();
            if (pendingTransactionId.containsKey(uuid)) continue;

            short id = (short) (nextTransactionId.getAndIncrement() & 0x7FFF);
            pendingTransactionId.put(uuid, id);
            pendingTransactionSentAt.put(uuid, System.currentTimeMillis());

            try {
                PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PING);
                packet.getIntegers().write(0, player.getEntityId());
                packet.getIntegers().write(1, (int) id);
                protocolManager.sendServerPacket(player, packet);
            } catch (Exception ex) {
                plugin.debug("DisablerCheck failed to send ping probe to " + player.getName() + ": " + ex.getMessage());
                pendingTransactionId.remove(uuid);
                pendingTransactionSentAt.remove(uuid);
            }
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (anticheat.getBypassGuard().shouldBypass(player, "disabler")) continue;
            UUID uuid = player.getUniqueId();
            Long sentAt = pendingTransactionSentAt.get(uuid);
            if (sentAt == null) continue;

            if (now - sentAt > rttThresholdMs) {
                int missed = missedTransactions.merge(uuid, 1, Integer::sum);
                if (missed >= requiredMissedToFlag) {
                    anticheat.flagRaw(player, "disabler", 2.0,
                        "missedTransactions=" + missed + " rttExceeded=" + (now - sentAt) + "ms");
                    missedTransactions.put(uuid, 0);
                }
                pendingTransactionId.remove(uuid);
                pendingTransactionSentAt.remove(uuid);
            }
        }
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PONG) return;
        Player player = event.getPlayer();
        if (player == null || anticheat.getBypassGuard().shouldBypass(player, "disabler")) return;
        UUID uuid = player.getUniqueId();

        Short expectedId = pendingTransactionId.remove(uuid);
        pendingTransactionSentAt.remove(uuid);
        if (expectedId == null) return;

        try {
            Integer readVal = event.getPacket().getIntegers().readSafely(0);
            if (readVal == null) return;
            int receivedId = readVal;
            if (receivedId != expectedId) {
                int wrong = wrongIdTransactions.merge(uuid, 1, Integer::sum);
                if (wrong >= requiredWrongIdToFlag) {
                    anticheat.flagRaw(player, "disabler", 2.0,
                        "wrongTransactionId expected=" + expectedId + " got=" + receivedId + " count=" + wrong);
                    wrongIdTransactions.put(uuid, 0);
                }
            } else {
                missedTransactions.computeIfPresent(uuid, (k, v) -> Math.max(0, v - 1));
                wrongIdTransactions.computeIfPresent(uuid, (k, v) -> Math.max(0, v - 1));
            }
        } catch (Exception ex) {
            plugin.debug("DisablerCheck failed to read pong: " + ex.getMessage());
        }
    }
}
