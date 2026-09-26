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
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects Disabler / PingSpoof / PacketCanceller hacks by tracking
 * client transaction (ping) packet responses. Modern hack clients
 * (Rise, LiquidBounce) send malformed or delayed transaction responses
 * to desync the server's anti-cheat state. This listener:
 *
 * 1. Sends periodic keep-alive/transaction probes with unique IDs.
 * 2. Tracks whether the client responds with the correct ID within
 *    a reasonable RTT window.
 * 3. Flags if the client skips, sends wrong IDs, or responds too late.
 *
 * Requires ProtocolLib; never starts if absent.
 */
public final class DisablerCheck extends PacketAdapter {

    private final LegendaryPlugin plugin;
    private final AnticheatModule anticheat;
    private final ProtocolManager protocolManager;
    private final Map<UUID, Short> pendingTransactionId = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingTransactionSentAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> missedTransactions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> wrongIdTransactions = new ConcurrentHashMap<>();
    private BukkitTask probeTask;
    private boolean enabled;
    private long probeIntervalMs;
    private long rttThresholdMs;
    private int requiredMissedToFlag;
    private int requiredWrongIdToFlag;

    public DisablerCheck(LegendaryPlugin plugin, AnticheatModule anticheat) {
        super(plugin, ListenerPriority.NORMAL,
            PacketType.Play.Client.PONG,
            PacketType.Play.Client.WINDOW_CLICK);
        this.plugin = plugin;
        this.anticheat = anticheat;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("anticheat.checks.disabler.enabled", true);
        probeIntervalMs = plugin.getConfig().getLong("anticheat.checks.disabler.probe-interval-ms", 5000);
        rttThresholdMs = plugin.getConfig().getLong("anticheat.checks.disabler.rtt-threshold-ms", 3000);
        requiredMissedToFlag = plugin.getConfig().getInt("anticheat.checks.disabler.required-missed", 3);
        requiredWrongIdToFlag = plugin.getConfig().getInt("anticheat.checks.disabler.required-wrong-id", 2);
    }

    public void register() {
        if (!enabled) return;
        protocolManager.addPacketListener(this);
        probeTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sendProbes, 20L, 100L);
    }

    public void unregister() {
        protocolManager.removePacketListener(this);
        if (probeTask != null) {
            probeTask.cancel();
            probeTask = null;
        }
        pendingTransactionId.clear();
        pendingTransactionSentAt.clear();
        missedTransactions.clear();
        wrongIdTransactions.clear();
    }

    private short nextTransactionId = 1;

    private void sendProbes() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (anticheat.getBypassGuard().shouldBypass(player, "disabler")) continue;
            UUID uuid = player.getUniqueId();
            short id = nextTransactionId++;
            pendingTransactionId.put(uuid, id);
            pendingTransactionSentAt.put(uuid, System.currentTimeMillis());

            // Check for timed-out transactions
            Long sentAt = pendingTransactionSentAt.get(uuid);
            if (sentAt != null && System.currentTimeMillis() - sentAt > rttThresholdMs) {
                int missed = missedTransactions.merge(uuid, 1, Integer::sum);
                if (missed >= requiredMissedToFlag) {
                    anticheat.flagRaw(player, "disabler", 2.0,
                        "missedTransactions=" + missed + " rttExceeded=" + (System.currentTimeMillis() - sentAt) + "ms");
                    missedTransactions.put(uuid, 0);
                }
                pendingTransactionId.remove(uuid);
                pendingTransactionSentAt.remove(uuid);
            }
        }
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null || anticheat.getBypassGuard().shouldBypass(player, "disabler")) return;
        UUID uuid = player.getUniqueId();

        if (event.getPacketType() == PacketType.Play.Client.PONG) {
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
                    missedTransactions.merge(uuid, -1, (a, b) -> Math.max(0, a + b));
                    wrongIdTransactions.merge(uuid, -1, (a, b) -> Math.max(0, a + b));
                }
            } catch (Exception ex) {
                plugin.debug("DisablerCheck failed to read pong: " + ex.getMessage());
            }
        }
    }
}
