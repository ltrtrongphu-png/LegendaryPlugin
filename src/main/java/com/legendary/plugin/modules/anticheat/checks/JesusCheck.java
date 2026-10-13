package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import com.legendary.plugin.util.BlockWhitelist;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NEW CHECK (2026-09-19 DeepSeek-dump merge review). Flags a player
 * standing on top of open water for several consecutive ticks without
 * swimming, a boat, a frost-walker path, or a legitimate non-solid
 * surface (lily pad, kelp, seagrass) under their feet - the classic
 * "Jesus"/water-walk hack signature. Grace windows after teleport and
 * vehicle-exit avoid flagging the instant after a boat ride ends.
 */
public final class JesusCheck extends Check implements Listener {

    private final Map<UUID, Integer> groundOnWaterTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();
    private int requiredTicks;

    public JesusCheck(AnticheatModule anticheat) {
        super(anticheat, "jesus", "Jesus");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        requiredTicks = ConfigUtil.getBoundedInt(plugin, path("required-ticks"), 4, 2, 40);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 1000);
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            graceUntil.put(player.getUniqueId(), System.currentTimeMillis() + 1500);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        Long grace = graceUntil.get(uuid);
        if (grace != null && System.currentTimeMillis() < grace) return;
        if (player.isSwimming() || player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) {
            groundOnWaterTicks.put(uuid, 0);
            return;
        }

        Material below = event.getTo().clone().subtract(0, 0.1, 0).getBlock().getType();
        Material atFeet = event.getTo().getBlock().getType();
        boolean standingOnWaterSurface = below == Material.WATER && !BlockWhitelist.NON_SOLID.contains(atFeet);

        // "onGround" as reported by the client is exactly what a Jesus hack fakes - so we
        // additionally require the player's vertical velocity to be ~0 (i.e. genuinely resting)
        // rather than trusting isOnGround() alone.
        boolean resting = Math.abs(player.getVelocity().getY()) < 0.05;

        if (standingOnWaterSurface && resting && !player.isInWater()) {
            int ticks = groundOnWaterTicks.merge(uuid, 1, Integer::sum);
            if (ticks > requiredTicks) {
                flag(player, 1.0, "waterWalkTicks=" + ticks);
                groundOnWaterTicks.put(uuid, 0);
            }
        } else {
            groundOnWaterTicks.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        groundOnWaterTicks.remove(uuid);
        graceUntil.remove(uuid);
    }
}
