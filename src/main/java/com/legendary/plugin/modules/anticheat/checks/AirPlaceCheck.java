package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AirPlace: detects clients that place blocks in mid-air without a
 * supporting surface. Vanilla requires the player to target an existing
 * block face to place against. Hack clients like AirPlace bypass this
 * by sending place packets without a valid target block. Detection:
 * when a block is placed, check that the block below (or an adjacent
 * face) is solid. If the placed block has no supporting neighbor and
 * the player is airborne, flag.
 */
public final class AirPlaceCheck extends Check implements Listener {

    private final Map<UUID, Long> lastPlaceTs = new ConcurrentHashMap<>();
    private long minIntervalMs;
    private int maxConsecutiveAirPlace;
    private final Map<UUID, Integer> airPlaceStreak = new ConcurrentHashMap<>();

    public AirPlaceCheck(AnticheatModule anticheat) {
        super(anticheat, "airplace", "AirPlace");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minIntervalMs = plugin.getConfig().getLong(path("min-interval-ms"), 100);
        maxConsecutiveAirPlace = plugin.getConfig().getInt(path("max-consecutive"), 3);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();

        var block = event.getBlockPlaced();
        var below = block.getLocation().clone().subtract(0, 1, 0).getBlock().getType();
        var north = block.getLocation().clone().subtract(1, 0, 0).getBlock().getType();
        var south = block.getLocation().clone().add(1, 0, 0).getBlock().getType();
        var east = block.getLocation().clone().add(0, 0, 1).getBlock().getType();
        var west = block.getLocation().clone().subtract(0, 0, 1).getBlock().getType();

        boolean hasSupport = isSolid(below) || isSolid(north) || isSolid(south)
            || isSolid(east) || isSolid(west);

        if (!hasSupport && !player.isOnGround()) {
            int streak = airPlaceStreak.merge(uuid, 1, Integer::sum);
            if (streak >= maxConsecutiveAirPlace) {
                flag(player, 1.0, String.format("airPlace streak=%d block=%s", streak, block.getType()));
                airPlaceStreak.put(uuid, 0);
            }
        } else {
            airPlaceStreak.put(uuid, 0);
        }

        lastPlaceTs.put(uuid, System.currentTimeMillis());
    }

    private boolean isSolid(Material m) {
        if (m.isAir()) return false;
        return m.isOccluding() || m == Material.GLASS || m.name().endsWith("_PLANKS")
            || m.name().endsWith("_LOG") || m.name().endsWith("_STONE")
            || m == Material.COBBLESTONE || m == Material.BEDROCK;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPlaceTs.remove(uuid);
        airPlaceStreak.remove(uuid);
    }
}
