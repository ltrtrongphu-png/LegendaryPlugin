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
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flags breaking/placing blocks or attacking while an inventory GUI is open
 * (inventory-move exploit). Also flags actions within a short grace window
 * after closing a GUI, since hack clients can send actions in the same tick
 * as the close packet.
 */
public final class InventoryActionCheck extends Check implements Listener {

    private final Set<UUID> guiOpen = ConcurrentHashMap.newKeySet();
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
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        guiOpen.add(player.getUniqueId());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        guiOpen.remove(player.getUniqueId());
        closeGraceUntil.put(player.getUniqueId(), System.currentTimeMillis() + graceMs);
    }

    private boolean isGuiActive(Player player) {
        UUID uuid = player.getUniqueId();
        if (guiOpen.contains(uuid)) return true;
        Long until = closeGraceUntil.get(uuid);
        return until != null && System.currentTimeMillis() < until;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        flagIfGuiActive(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        flagIfGuiActive(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker) flagIfGuiActive(attacker);
    }

    private void flagIfGuiActive(Player player) {
        if (shouldSkip(player)) return;
        if (isGuiActive(player)) {
            flag(player, 0.5, "action-while-gui-open");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        guiOpen.remove(uuid);
        closeGraceUntil.remove(uuid);
    }
}
