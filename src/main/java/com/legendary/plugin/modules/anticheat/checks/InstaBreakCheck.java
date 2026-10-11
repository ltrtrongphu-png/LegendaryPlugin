package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * NEW CHECK (comprehensive-upgrade addition). A survival/adventure player
 * legitimately breaking bedrock, barriers, command blocks, structure
 * blocks, jigsaws or end-gateways is not possible on an unmodified
 * client/server - if it happens, the client is either exploiting a dupe/
 * block-breaking bug or has a hacked "instabreak any block" feature. This
 * is a cheap, deterministic, very-high-confidence signature, so it uses a
 * high weight and a short escalation by default (configurable).
 */
public final class InstaBreakCheck extends Check implements Listener {

    private static final Set<Material> DEFAULT_PROTECTED = EnumSet.of(
        Material.BEDROCK, Material.BARRIER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID,
        Material.JIGSAW, Material.END_GATEWAY, Material.END_PORTAL_FRAME, Material.LIGHT);

    private Set<Material> protectedBlocks = DEFAULT_PROTECTED;
    private double weight;

    public InstaBreakCheck(AnticheatModule anticheat) {
        super(anticheat, "instabreak", "InstaBreak");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        weight = plugin.getConfig().getDouble(path("weight"), 5.0);
        var configured = plugin.getConfig().getStringList(path("materials"));
        if (configured.isEmpty()) {
            protectedBlocks = DEFAULT_PROTECTED;
        } else {
            Set<Material> set = EnumSet.noneOf(Material.class);
            for (String name : configured) {
                try {
                    set.add(Material.valueOf(name.trim().toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Unknown material in " + path("materials") + ": " + name);
                }
            }
            protectedBlocks = set.isEmpty() ? DEFAULT_PROTECTED : set;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.isOp()) return;
        if (protectedBlocks.contains(event.getBlock().getType())) {
            event.setCancelled(true);
            flag(player, weight, "brokeProtected=" + event.getBlock().getType());
        }
    }
}
