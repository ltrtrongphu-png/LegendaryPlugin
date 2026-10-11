package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags a totem being refilled into the offhand faster than a human can manually swap it in. */
public final class AutoTotemCheck extends Check implements Listener {

    private final Map<UUID, Long> offhandTotemLostAt = new ConcurrentHashMap<>();
    private long minRefillMs;

    public AutoTotemCheck(AnticheatModule anticheat) {
        super(anticheat, "autototem", "AutoTotem");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minRefillMs = ConfigUtil.getBoundedLong(plugin, path("min-refill-ms"), 80, 10, 1000);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (shouldSkip(player)) return;
        checkOffhandForTotemLoss(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && !shouldSkip(player)) {
            checkOffhandForTotemRefill(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!shouldSkip(event.getPlayer())) checkOffhandForTotemRefill(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        if (!shouldSkip(event.getPlayer())) checkOffhandForTotemRefill(event.getPlayer());
    }

    private void checkOffhandForTotemLoss(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand.getType() != Material.TOTEM_OF_UNDYING) {
            offhandTotemLostAt.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    private void checkOffhandForTotemRefill(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand.getType() != Material.TOTEM_OF_UNDYING) return;
        Long lostAt = offhandTotemLostAt.get(player.getUniqueId());
        if (lostAt != null && System.currentTimeMillis() - lostAt < minRefillMs) {
            flag(player, 1.0, "refill=" + (System.currentTimeMillis() - lostAt) + "ms");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        offhandTotemLostAt.remove(event.getPlayer().getUniqueId());
    }
}
