package com.legendary.plugin.integrations;

import com.legendary.plugin.LegendaryPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class BungeeIntegration implements PluginMessageListener, Listener {

    private static final String PROXY_CHANNEL = "BungeeCord";
    private static final String BAN_CHANNEL = "legendary:ban_sync";
    private static final int MAX_PAYLOAD_BYTES = 4096;
    private static final long MAX_CLOCK_SKEW_MS = 30_000L;
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");
    private static final Pattern DURATION = Pattern.compile("^(\\d{1,5})([smhdw])$", Pattern.CASE_INSENSITIVE);

    private final LegendaryPlugin plugin;
    private final Map<String, Long> acceptedNonces = new ConcurrentHashMap<>();
    private final Map<UUID, SyncedBan> pendingBans = new ConcurrentHashMap<>();
    private volatile byte[] secret;
    private boolean registered;

    public BungeeIntegration(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean register() {
        if (registered) return true;
        String configuredSecret = plugin.getConfig().getString("anticheat.bungee-sync.secret", "");
        if (configuredSecret == null || configuredSecret.isBlank()) {
            configuredSecret = System.getenv("LEGENDARY_BUNGEE_SYNC_SECRET");
        }
        if (configuredSecret == null || configuredSecret.isBlank()) {
            plugin.getLogger().warning("Bungee ban sync disabled: configure anticheat.bungee-sync.secret "
                + "or LEGENDARY_BUNGEE_SYNC_SECRET on every server.");
            return false;
        }
        secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            secret = null;
            plugin.getLogger().severe("Bungee ban-sync secret must contain at least 32 UTF-8 bytes; sync disabled.");
            return false;
        }
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, PROXY_CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, BAN_CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registered = true;
        return true;
    }

    public void unregister() {
        if (!registered) return;
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(plugin, PROXY_CHANNEL);
        messenger.unregisterIncomingPluginChannel(plugin, BAN_CHANNEL, this);
        org.bukkit.event.HandlerList.unregisterAll(this);
        acceptedNonces.clear();
        pendingBans.clear();
        secret = null;
        registered = false;
    }

    public void broadcastBan(UUID uuid, String name, String reason, String duration) {
        byte[] key = secret;
        if (!registered || key == null) return;
        if (uuid == null || name == null || !PLAYER_NAME.matcher(name).matches()
            || reason == null || reason.length() > 512 || !validDuration(duration)) {
            plugin.getLogger().warning("Refused to broadcast invalid Bungee ban-sync data.");
            return;
        }
        Player relay = plugin.getServer().getOnlinePlayers().stream().findAny().orElse(null);
        if (relay == null) {
            plugin.getLogger().warning("Cannot sync a ban: no online player is available to relay through the proxy.");
            return;
        }
        try {
            long timestamp = System.currentTimeMillis();
            String nonce = UUID.randomUUID().toString();
            byte[] signed = encodeFields(timestamp, nonce, uuid, name, reason, duration);
            byte[] signature = hmac(key, signed);
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            payloadBytes.write(signed);
            DataOutputStream payload = new DataOutputStream(payloadBytes);
            payload.writeUTF(HexFormat.of().formatHex(signature));
            payload.flush();
            if (payloadBytes.size() > MAX_PAYLOAD_BYTES) {
                plugin.getLogger().warning("Refused oversized Bungee ban-sync payload.");
                return;
            }

            ByteArrayOutputStream outerBytes = new ByteArrayOutputStream();
            DataOutputStream outer = new DataOutputStream(outerBytes);
            outer.writeUTF("Forward");
            outer.writeUTF("ALL");
            outer.writeUTF(BAN_CHANNEL);
            outer.writeShort(payloadBytes.size());
            outer.write(payloadBytes.toByteArray());
            outer.flush();
            relay.sendPluginMessage(plugin, PROXY_CHANNEL, outerBytes.toByteArray());
        } catch (java.io.IOException | GeneralSecurityException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to broadcast authenticated ban-sync message.", exception);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player relay, byte[] message) {
        if (!BAN_CHANNEL.equals(channel) || relay == null || message == null
            || message.length == 0 || message.length > MAX_PAYLOAD_BYTES) return;
        byte[] key = secret;
        if (!registered || key == null) return;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            long timestamp = input.readLong();
            String nonce = input.readUTF();
            UUID uuid = UUID.fromString(input.readUTF());
            String name = input.readUTF();
            String reason = input.readUTF();
            String duration = input.readUTF();
            String providedSignature = input.readUTF();
            if (input.available() != 0 || nonce.length() > 64 || name.length() > 16
                || reason.length() > 512 || !PLAYER_NAME.matcher(name).matches()
                || !validDuration(duration)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (timestamp < now - MAX_CLOCK_SKEW_MS || timestamp > now + MAX_CLOCK_SKEW_MS) return;
            byte[] signed = encodeFields(timestamp, nonce, uuid, name, reason, duration);
            byte[] expected = hmac(key, signed);
            byte[] provided;
            try {
                provided = HexFormat.of().parseHex(providedSignature);
            } catch (IllegalArgumentException invalidHex) {
                return;
            }
            if (!MessageDigest.isEqual(expected, provided)) return;

            acceptedNonces.entrySet().removeIf(entry -> now - entry.getValue() > MAX_CLOCK_SKEW_MS);
            if (acceptedNonces.size() >= 4096 || acceptedNonces.putIfAbsent(nonce, now) != null) return;
            SyncedBan ban = new SyncedBan(uuid, name, reason, expiration(duration));
            if (pendingBans.size() < 10_000 || pendingBans.containsKey(uuid)) {
                pendingBans.put(uuid, ban);
            }
            Player local = Bukkit.getPlayer(uuid);
            if (local != null) applyBan(local, ban);
            plugin.getLogger().info("[BungeeSync] Authenticated ban received for " + name + ".");
        } catch (java.io.IOException | GeneralSecurityException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Rejected malformed Bungee ban-sync message.", exception);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        SyncedBan ban = pendingBans.remove(event.getPlayer().getUniqueId());
        if (ban != null) applyBan(event.getPlayer(), ban);
    }

    private void applyBan(Player player, SyncedBan ban) {
        pendingBans.remove(ban.uuid());
        try {
            player.ban(ban.reason(), ban.expiresAt(), "LegendaryPlugin Bungee sync");
            player.kick(Component.text("Banned: " + ban.reason()));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                "Failed to apply authenticated network ban to " + ban.name() + ".", exception);
        }
    }

    private static byte[] encodeFields(long timestamp, String nonce, UUID uuid,
                                       String name, String reason, String duration) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeLong(timestamp);
        output.writeUTF(nonce);
        output.writeUTF(uuid.toString());
        output.writeUTF(name);
        output.writeUTF(reason);
        output.writeUTF(duration);
        output.flush();
        return bytes.toByteArray();
    }

    private static byte[] hmac(byte[] key, byte[] message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    static boolean validDuration(String value) {
        if (value == null) return false;
        var matcher = DURATION.matcher(value);
        return matcher.matches() && Long.parseLong(matcher.group(1)) > 0;
    }

    private static Date expiration(String value) {
        var matcher = DURATION.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("Unsupported ban duration");
        long amount = Long.parseLong(matcher.group(1));
        ChronoUnit unit = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "s" -> ChronoUnit.SECONDS;
            case "m" -> ChronoUnit.MINUTES;
            case "h" -> ChronoUnit.HOURS;
            case "d" -> ChronoUnit.DAYS;
            case "w" -> ChronoUnit.WEEKS;
            default -> throw new IllegalArgumentException("Unsupported ban duration");
        };
        return Date.from(Instant.now().plus(amount, unit));
    }

    private record SyncedBan(UUID uuid, String name, String reason, Date expiresAt) {}
}
