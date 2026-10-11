package com.legendary.plugin.integrations;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * NEW FEATURE (2026-09-19 merge, scoped-down from the DeepSeek dump's
 * integrations.BungeeIntegration). Broadcasts a ban to every server
 * behind the same BungeeCord/Velocity proxy the instant it happens on
 * this one, instead of the cheater simply reconnecting to a sibling
 * server that hasn't banned them yet.
 *
 * Uses the standard, long-stable "BungeeCord" reserved plugin-messaging
 * channel with its "Forward" sub-command (the only way a Spigot/Paper
 * server can talk to its siblings - there is no direct server-to-server
 * link, everything must be relayed through the proxy). This requires:
 *   - At least one player online to act as the relay (Bungee plugin
 *     messages can only be sent *through* a connected player's channel).
 *   - The proxy's config.yml (BungeeCord) or velocity.toml (Velocity,
 *     which bridges the legacy channel automatically) to have plugin
 *     messaging enabled, which is the default.
 * Entirely opt-in via anticheat.bungee-sync.enabled (default false) -
 * a single, non-networked server should leave this off.
 */
public final class BungeeIntegration implements PluginMessageListener {

    private static final String CHANNEL = "BungeeCord";
    private static final String SUB_CHANNEL = "legendary:ban_sync";

    private final LegendaryPlugin plugin;
    private boolean registered = false;

    public BungeeIntegration(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        if (registered) return;
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
        registered = true;
    }

    public void unregister() {
        if (!registered) return;
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL);
        messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        registered = false;
    }

    /** Call right after a local ban is applied. No-op if not registered/no player online to relay through. */
    public void broadcastBan(UUID uuid, String name, String reason, String duration) {
        if (!registered) return;
        Player relay = plugin.getServer().getOnlinePlayers().stream().findAny().orElse(null);
        if (relay == null) {
            plugin.getLogger().warning("Cannot sync ban across the network - no player online on this server to relay the message through.");
            return;
        }
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(payloadBytes);
            payload.writeUTF(uuid.toString());
            payload.writeUTF(name);
            payload.writeUTF(reason);
            payload.writeUTF(duration);

            ByteArrayOutputStream outer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(outer);
            out.writeUTF("Forward");
            out.writeUTF("ALL");
            out.writeUTF(SUB_CHANNEL);
            out.writeShort(payloadBytes.size());
            out.write(payloadBytes.toByteArray());

            relay.sendPluginMessage(plugin, CHANNEL, outer.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to broadcast ban across the network: " + e.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(CHANNEL)) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            String subChannel = in.readUTF();
            if (!subChannel.equals(SUB_CHANNEL)) return;
            short length = in.readShort();
            byte[] payloadBytes = new byte[length];
            in.readFully(payloadBytes);

            DataInputStream payloadIn = new DataInputStream(new ByteArrayInputStream(payloadBytes));
            UUID uuid = UUID.fromString(payloadIn.readUTF());
            String name = payloadIn.readUTF();
            String reason = payloadIn.readUTF();

            // Apply locally (kick if online now) WITHOUT re-broadcasting - this message already
            // came from the proxy relay, so re-sending it would create an infinite relay loop
            // across every server on the network.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player local = plugin.getServer().getPlayer(uuid);
                if (local != null) {
                    local.kick(Text.of("<red>" + reason));
                }
                plugin.getLogger().info("[BungeeSync] Applied network ban for " + name + ": " + reason);
            });
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warning("Failed to parse incoming ban-sync message: " + e.getMessage());
        }
    }
}
