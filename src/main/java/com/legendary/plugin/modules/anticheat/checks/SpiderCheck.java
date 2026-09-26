package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags a player staying airborne against a wall far longer than a natural jump allows (wall-climb / spider hacks). */
public final class SpiderCheck extends Check implements Listener {

    private final Map<UUID, Integer> airborneTicks = new ConcurrentHashMap<>();
    private int naturalJumpTicks;
    private int requiredTicks;

    public SpiderCheck(AnticheatModule anticheat) {
        super(anticheat, "spider", "Spider");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        naturalJumpTicks = ConfigUtil.getBoundedInt(plugin, path("natural-jump-ticks"), 10, 4, 40);
        requiredTicks = ConfigUtil.getBoundedInt(plugin, path("required-ticks"), 6, 2, 40);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        if (player.isOnGround() || player.isFlying() || player.getAllowFlight()
                || player.isInWater() || player.isInLava() || player.isClimbing()
                || player.isGliding() || player.isInsideVehicle()) {
            airborneTicks.remove(uuid);
            return;
        }
        if (!isAgainstWall(player)) {
            airborneTicks.remove(uuid);
            return;
        }
        double compensation = anticheat.getTpsMonitor().getCompensationFactor();
        int effectiveRequired = (int) Math.ceil(requiredTicks * compensation);
        int ticks = airborneTicks.merge(uuid, 1, Integer::sum);
        if (ticks > naturalJumpTicks + effectiveRequired) {
            flag(player, 1.0, "airborneAgainstWallTicks=" + ticks);
            airborneTicks.put(uuid, 0);
        }
    }

    private boolean isAgainstWall(Player player) {
        if (player.isClimbing()) return false;
        var loc = player.getLocation();
        var feetBlock = loc.getBlock();
        if (feetBlock.getType() == org.bukkit.Material.LADDER
                || feetBlock.getType() == org.bukkit.Material.VINE
                || feetBlock.getType() == org.bukkit.Material.SCAFFOLDING
                || feetBlock.getType() == org.bukkit.Material.WEEPING_VINES
                || feetBlock.getType() == org.bukkit.Material.TWISTING_VINES) {
            return false;
        }
        for (var face : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            var relative = loc.getBlock().getRelative(face);
            if (!relative.isEmpty() && relative.getType().isSolid()) return true;
        }
        return false;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        airborneTicks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        airborneTicks.remove(event.getPlayer().getUniqueId());
    }
}
