package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GhostHand: detects clients that interact with or open containers through
 * solid blocks without line-of-sight. Hack clients use this to loot chests,
 * open doors, and press buttons through walls. Detection: on interact/open,
 * raycast from the player's eye to the block - if the raycast hits a
 * different block first, the player is reaching through something solid.
 */
public final class GhostHandCheck extends Check implements Listener {

    private double maxReach;
    private boolean checkContainers;
    private boolean checkDoorsButtons;
    private final Map<UUID, Long> lastFlagTime = new ConcurrentHashMap<>();
    private long flagCooldownMs;

    public GhostHandCheck(AnticheatModule anticheat) {
        super(anticheat, "ghosthand", "GhostHand");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxReach = plugin.getConfig().getDouble(path("max-reach"), 5.0);
        checkContainers = plugin.getConfig().getBoolean(path("check-containers"), true);
        checkDoorsButtons = plugin.getConfig().getBoolean(path("check-doors-buttons"), true);
        flagCooldownMs = plugin.getConfig().getLong(path("flag-cooldown-ms"), 1000);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!event.hasBlock()) return;
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        Material mat = clicked.getType();
        boolean isContainer = isContainer(mat);
        boolean isDoorButton = isDoorOrButton(mat);

        if (!isContainer && !isDoorButton) return;
        if (isContainer && !checkContainers) return;
        if (isDoorButton && !checkDoorsButtons) return;

        double distSq = player.getEyeLocation().distanceSquared(clicked.getLocation().add(0.5, 0.5, 0.5));
        if (distSq > maxReach * maxReach) return;

        if (!com.legendary.plugin.util.RaycastUtils.hasLineOfSight(
                player.getEyeLocation(), clicked.getLocation().add(0.5, 0.5, 0.5), 0.7)) {
            long now = System.currentTimeMillis();
            Long last = lastFlagTime.get(player.getUniqueId());
            if (last != null && now - last < flagCooldownMs) return;
            lastFlagTime.put(player.getUniqueId(), now);
            flag(player, 1.5, String.format("block=%s dist=%.2f", mat, Math.sqrt(distSq)));
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (shouldSkip(player)) return;
        if (!checkContainers) return;
        var loc = event.getInventory().getLocation();
        if (loc == null) return;
        double distSq = player.getEyeLocation().distanceSquared(loc.add(0.5, 0.5, 0.5));
        if (distSq > maxReach * maxReach) return;
        if (!com.legendary.plugin.util.RaycastUtils.hasLineOfSight(
                player.getEyeLocation(), loc, 0.7)) {
            long now = System.currentTimeMillis();
            Long last = lastFlagTime.get(player.getUniqueId());
            if (last != null && now - last < flagCooldownMs) return;
            lastFlagTime.put(player.getUniqueId(), now);
            flag(player, 1.5, String.format("container through wall dist=%.2f", Math.sqrt(distSq)));
        }
    }

    private boolean isContainer(Material mat) {
        return mat.name().contains("CHEST") || mat.name().contains("SHULKER_BOX")
            || mat == Material.BARREL || mat == Material.ENDER_CHEST
            || mat == Material.HOPPER || mat == Material.DISPENSER
            || mat == Material.DROPPER || mat.name().contains("FURNACE");
    }

    private boolean isDoorOrButton(Material mat) {
        return mat.name().contains("DOOR") || mat == Material.STONE_BUTTON
            || mat.name().contains("BUTTON") || mat == Material.LEVER
            || mat.name().contains("PRESSURE_PLATE") || mat.name().contains("TRAPDOOR");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastFlagTime.remove(event.getPlayer().getUniqueId());
    }
}
