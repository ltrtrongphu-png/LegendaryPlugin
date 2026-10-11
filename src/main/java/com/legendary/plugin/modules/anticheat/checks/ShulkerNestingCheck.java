package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * NEW CHECK (2026-09-19 DeepSeek-dump merge review) - a server-safety
 * fix as much as an anti-cheat one. Placing a shulker box that itself
 * contains another shulker box ("shulker nesting") is a known vanilla
 * dupe/crash exploit vector on some server versions; this check inspects
 * the block-item's stored contents before it's ever placed and cancels
 * it outright, regardless of violation-level thresholds - there is no
 * legitimate reason for a player-placed shulker box item to contain
 * another shulker box.
 */
public final class ShulkerNestingCheck extends Check implements Listener {

    public ShulkerNestingCheck(AnticheatModule anticheat) {
        super(anticheat, "shulkernesting", "ShulkerNesting");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        ItemStack inHand = event.getItemInHand();
        if (inHand == null || !inHand.getType().name().endsWith("SHULKER_BOX")) return;
        if (!(inHand.getItemMeta() instanceof BlockStateMeta meta)) return;
        BlockState state = meta.getBlockState();
        if (!(state instanceof ShulkerBox shulkerBox)) return;

        for (ItemStack contained : shulkerBox.getInventory().getContents()) {
            if (contained != null && contained.getType().name().endsWith("SHULKER_BOX")) {
                event.setCancelled(true);
                flag(player, 3.0, "attempted to place nested shulker box");
                return;
            }
        }
    }
}
