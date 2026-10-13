package com.legendary.plugin.modules.antichunkban;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Reports unusually dense chunks without blocking, quarantining, or moving players.
 * Inspection is limited to chunk transitions and chunks that are already loaded.
 */
public final class AntiChunkBanListener implements Listener {

    private final LegendaryPlugin plugin;
    private final AntiChunkBanManager manager;

    public AntiChunkBanListener(LegendaryPlugin plugin, AntiChunkBanManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        int fromX = event.getFrom().getBlockX() >> 4;
        int fromZ = event.getFrom().getBlockZ() >> 4;
        int toX = event.getTo().getBlockX() >> 4;
        int toZ = event.getTo().getBlockZ() >> 4;
        if (fromX == toX && fromZ == toZ) return;
        inspect(event.getPlayer(), event.getTo().getWorld(), toX, toZ);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        inspect(event.getPlayer(), event.getTo().getWorld(),
            event.getTo().getBlockX() >> 4, event.getTo().getBlockZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var location = event.getPlayer().getLocation();
        inspect(event.getPlayer(), location.getWorld(),
            location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private void inspect(Player player, World world, int chunkX, int chunkZ) {
        if (world == null || player.hasPermission("legendary.antichunkban.bypass")
            || !world.isChunkLoaded(chunkX, chunkZ)) return;
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (!manager.isChunkDangerous(chunk)) return;

        String message = "[AntiChunkBan] Dense chunk " + chunkX + "," + chunkZ
            + " in " + world.getName() + " encountered by " + player.getName()
            + "; no movement or gameplay action was blocked.";
        plugin.getLogger().warning(message);
        for (Player staff : world.getPlayers()) {
            if (staff.hasPermission("legendary.anticheat.alerts")) {
                staff.sendMessage(message);
            }
        }
    }
}
