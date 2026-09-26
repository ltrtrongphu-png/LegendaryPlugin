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

/**
 * NEW CHECK (2026-09-20, gap found by reviewing a hack client's module
 * list: movement/Blink). "Blink" withholds a player's outgoing movement
 * packets client-side for a while (the player appears frozen to
 * everyone else) and then releases them all in a burst, letting the
 * player act "in the past" from the server's perspective before
 * suddenly snapping to their real position. The server-visible
 * signature is distinctive: an abnormally long real-time gap between
 * two consecutive movement updates, followed immediately by a position
 * change far larger than vanilla movement speed could cover in that
 * elapsed time. Ordinary lag produces gaps too, so this is compensated
 * by {@link com.legendary.plugin.modules.anticheat.TpsMonitor}, and any
 * teleport resets the timer so legitimate teleports are never flagged.
 */
public final class BlinkCheck extends Check implements Listener {

    private final Map<UUID, Long> lastMoveAt = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.Location> lastLocation = new ConcurrentHashMap<>();
    private long minGapMs;
    private double maxVanillaBlocksPerSecond;

    public BlinkCheck(AnticheatModule anticheat) {
        super(anticheat, "blink", "Blink");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minGapMs = ConfigUtil.getBoundedLong(plugin, path("min-gap-ms"), 400, 150, 5000);
        maxVanillaBlocksPerSecond = plugin.getConfig().getDouble(path("max-blocks-per-second"), 12.0);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastMoveAt.put(uuid, System.currentTimeMillis());
        lastLocation.put(uuid, event.getTo());
    }

    @EventHandler
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            lastMoveAt.put(player.getUniqueId(), System.currentTimeMillis());
            lastLocation.put(player.getUniqueId(), player.getLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long last = lastMoveAt.put(uuid, now);
        org.bukkit.Location from = lastLocation.put(uuid, event.getTo());
        if (last == null || from == null || from.getWorld() == null
            || !from.getWorld().equals(event.getTo().getWorld())) {
            return;
        }

        long elapsedMs = now - last;
        if (elapsedMs < minGapMs) return;

        if (player.isGliding() || player.isInsideVehicle() || player.isRiptiding()) return;

        double distance = from.distance(event.getTo());
        double compensation = anticheat.getTpsMonitor().getCompensationFactor();
        double maxDistanceForGap = (maxVanillaBlocksPerSecond * compensation) * (elapsedMs / 1000.0);
        if (distance > maxDistanceForGap) {
            flag(player, 1.0, String.format("gap=%dms distance=%.1f maxForGap=%.1f", elapsedMs, distance, maxDistanceForGap));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastMoveAt.remove(uuid);
        lastLocation.remove(uuid);
    }
}
