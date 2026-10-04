package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NEW CHECK (2026-09-20, gap found by reviewing a hack client's module
 * list: movement/GUIMove). Vanilla lets a player shuffle only slightly
 * while a container/crafting-table GUI is open - walking any real
 * distance away should close it. A "GUIMove" hack keeps the GUI open
 * while moving and even sprinting/jumping freely. This tracks
 * cumulative horizontal distance moved since a non-player inventory was
 * opened and flags (and force-closes the inventory) once it exceeds a
 * small allowance.
 */
public final class GUIMoveCheck extends Check implements Listener {

    private final Map<UUID, org.bukkit.Location> openedAt = new ConcurrentHashMap<>();
    private double maxDistanceWhileOpen;

    public GUIMoveCheck(AnticheatModule anticheat) {
        super(anticheat, "guimove", "GUIMove");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxDistanceWhileOpen = plugin.getConfig().getDouble(path("max-distance-while-open"), 2.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (isTrackedContainer(event.getInventory().getType())) {
            openedAt.put(player.getUniqueId(), player.getLocation());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openedAt.remove(player.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        var start = openedAt.get(player.getUniqueId());
        if (start == null || start.getWorld() == null || !start.getWorld().equals(event.getTo().getWorld())) return;

        double horizontal = Math.hypot(event.getTo().getX() - start.getX(), event.getTo().getZ() - start.getZ());
        if (horizontal > maxDistanceWhileOpen) {
            flag(player, 1.0, String.format("movedWhileGuiOpen=%.1f", horizontal));
            player.closeInventory(); // corrective action, same as vanilla would have done
            openedAt.remove(player.getUniqueId());
        }
    }

    private boolean isTrackedContainer(InventoryType type) {
        return switch (type) {
            case PLAYER, CREATIVE, CRAFTING -> false; // these don't have vanilla's distance auto-close rule
            default -> true;
        };
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        openedAt.remove(event.getPlayer().getUniqueId());
    }
}
