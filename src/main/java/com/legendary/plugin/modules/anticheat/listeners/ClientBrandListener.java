package com.legendary.plugin.modules.anticheat.listeners;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientBrandListener implements Listener, PluginMessageListener {

    private final LegendaryPlugin plugin;
    private List<String> suspiciousBrands = List.of();
    private String alertPermission = "legendary.anticheat.alerts";
    private final Map<UUID, String> playerBrands = new ConcurrentHashMap<>();

    public ClientBrandListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        suspiciousBrands = plugin.getConfig()
            .getStringList("anticheat.checks.clientbrand.suspicious-brands")
            .stream().map(String::toLowerCase).toList();
        alertPermission = plugin.getConfig()
            .getString("anticheat.checks.clientbrand.alert-permission",
                       "legendary.anticheat.alerts");
        if (!Bukkit.getMessenger().isIncomingChannelRegistered(plugin, "minecraft:brand")) {
            Bukkit.getMessenger().registerIncomingPluginChannel(
                plugin, "minecraft:brand", this
            );
        }
    }

    public void unregister() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, "minecraft:brand", this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("minecraft:brand")) return;

        String brand = decodeBrand(message);
        if (brand.isEmpty()) return;

        playerBrands.put(player.getUniqueId(), brand);

        String lower = brand.toLowerCase();
        for (String suspicious : suspiciousBrands) {
            if (lower.contains(suspicious)) {
                var msg = Text.of(
                    "<gray>[<gold>AC<gray>] <yellow><player> <gray>brand: <red><brand>",
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.unparsed("brand", brand)
                );
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission(alertPermission)) staff.sendMessage(msg);
                }
                plugin.getLogger().warning(
                    "[ClientBrand] " + player.getName() + " brand=" + brand
                );
                return;
            }
        }
    }

    /** Giải mã payload VarInt-prefixed String của minecraft:brand */
    private String decodeBrand(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int length = readVarInt(in);
            if (length <= 0 || length > 256) return "";
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private int readVarInt(DataInputStream in) throws IOException {
        int value = 0, position = 0;
        while (true) {
            int current = in.readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IOException("VarInt too big");
        }
        return value;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerBrands.remove(event.getPlayer().getUniqueId());
    }
}
