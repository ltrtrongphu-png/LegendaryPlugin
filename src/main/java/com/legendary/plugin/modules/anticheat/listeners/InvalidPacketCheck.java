package com.legendary.plugin.modules.anticheat.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.anticheat.AnticheatModule;

/**
 * Flags structurally invalid / out-of-range incoming packets (e.g. NaN or
 * out-of-world-bound position updates) that legitimate vanilla clients
 * never send. Requires ProtocolLib; the module simply never starts this
 * listener if ProtocolLib is absent. Ported from BaB.InvalidPacketCheck.
 */
public final class InvalidPacketCheck extends PacketAdapter {

    private final LegendaryPlugin plugin;
    private final AnticheatModule anticheat;

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
    }

    public void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        var player = event.getPlayer();
        if (player == null || anticheat.getBypassGuard().shouldBypass(player, "invalidpacket")) return;
        try {
            var packet = event.getPacket();
            for (double coordinate : packet.getDoubles().getValues()) {
                if (Double.isNaN(coordinate) || Double.isInfinite(coordinate) || Math.abs(coordinate) > 3.0E7) {
                    anticheat.flagRaw(player, "invalidpacket", 2.0, "bad coordinate=" + coordinate);
                    event.setCancelled(true);
                    return;
                }
            }
            for (float angle : packet.getFloat().getValues()) {
                if (Float.isNaN(angle) || Float.isInfinite(angle)) {
                    anticheat.flagRaw(player, "invalidpacket", 2.0, "bad angle=" + angle);
                    event.setCancelled(true);
                    return;
                }
            }
        } catch (Exception ex) {
            plugin.debug("InvalidPacketCheck failed to read packet: " + ex.getMessage());
        }
    }
}
