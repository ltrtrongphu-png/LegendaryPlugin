package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags breaking/placing blocks or attacking within a short grace window after closing an inventory GUI (inventory-move exploit). */
public final class InventoryActionCheck extends Check implements Listener {

    private final Map<UUID, Long> closeGraceUntil = new ConcurrentHashMap<>();
    private long graceMs;

    public InventoryActionCheck(AnticheatModule anticheat) {
        super(anticheat, "inventoryaction", "InventoryAction");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        graceMs = ConfigUtil.getBoundedLong(plugin, path("grace-ms"), 150, 0, 2000);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        closeGraceUntil.put(player.getUniqueId(), System.currentTimeMillis() + graceMs);
    }

    private boolean hasGuiOpen(Player player) {
        Long until = closeGraceUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        flagIfInGrace(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        flagIfInGrace(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker) flagIfInGrace(attacker);
    }

    private void flagIfInGrace(Player player) {
        if (shouldSkip(player)) return;
        if (hasGuiOpen(player)) {
            flag(player, 0.5, "action-during-close-grace");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        closeGraceUntil.remove(event.getPlayer().getUniqueId());
    }
}
