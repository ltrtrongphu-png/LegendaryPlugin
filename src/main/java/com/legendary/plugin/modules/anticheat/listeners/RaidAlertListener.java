package com.legendary.plugin.modules.anticheat.listeners;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerLoginEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Alerts staff when an unfamiliar/newly-joined player targets a
 * high-value container (chest raiding heuristics). Ported from
 * BaB.RaidAlertListener.
 */
public final class RaidAlertListener implements Listener {

    private final LegendaryPlugin plugin;
    private Set<Material> highValueContainers = EnumSet.noneOf(Material.class);

    public RaidAlertListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        highValueContainers.clear();
        for (String name : plugin.getConfig().getStringList("anticheat.checks.raidalert.high-value-containers")) {
            try {
                highValueContainers.add(Material.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !highValueContainers.contains(block.getType())) return;
        alert(event.getPlayer(), "opened", block.getType());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!highValueContainers.contains(event.getBlock().getType())) return;
        alert(event.getPlayer(), "broke", event.getBlock().getType());
    }

    private void alert(Player player, String verb, Material type) {
        if (plugin.getAnticheatModule().getBypassGuard().shouldBypass(player, "raidalert")) return;
        var message = Text.of("<gray>[<gold>RaidAlert<gray>] <yellow><player> <gray><verb> <white><block>",
            Placeholder.unparsed("player", player.getName()),
            Placeholder.unparsed("verb", verb),
            Placeholder.unparsed("block", type.name().toLowerCase()));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("legendary.anticheat.alerts")) staff.sendMessage(message);
        }
    }
}
