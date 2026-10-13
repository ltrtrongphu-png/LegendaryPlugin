package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NEW CHECK (comprehensive-upgrade addition). MovementCheck deliberately
 * ignores gliding players (elytra flight is not normal ground/air
 * movement), which left elytra fly-hacks completely unmonitored - a real
 * gap in the ported BaB checks. This check watches horizontal speed and
 * sustained altitude *gain* while gliding, with a grace window after any
 * firework-rocket use (the one legitimate way to gain speed/altitude
 * while gliding) so real players boosting with fireworks are not flagged.
 */
public final class ElytraFlightCheck extends Check implements Listener {

    private final Map<UUID, Long> fireworkBoostGraceUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> climbStreak = new ConcurrentHashMap<>();
    private double maxHorizontalSpeed;
    private double maxSustainedClimbPerTick;
    private int requiredStreak;
    private long fireworkGraceMs;

    public ElytraFlightCheck(AnticheatModule anticheat) {
        super(anticheat, "elytraflight", "ElytraFlight");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxHorizontalSpeed = plugin.getConfig().getDouble(path("max-horizontal-speed"), 2.4);
        maxSustainedClimbPerTick = plugin.getConfig().getDouble(path("max-sustained-climb-per-tick"), 0.35);
        requiredStreak = ConfigUtil.getBoundedInt(plugin, path("required-streak"), 8, 2, 40);
        fireworkGraceMs = ConfigUtil.getBoundedLong(plugin, path("firework-grace-ms"), 3000, 500, 15000);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) return;
        var item = event.getItem();
        if (item != null && item.getType() == Material.FIREWORK_ROCKET && event.getPlayer().isGliding()) {
            fireworkBoostGraceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + fireworkGraceMs);
        }
    }

    @EventHandler
    public void onToggleGlide(EntityToggleGlideEvent event) {
       if (!(event.getEntity() instanceof Player player)) return;
       if (!event.isGliding()) {
        climbStreak.remove(player.getUniqueId());
       }
   }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player) || !player.isGliding()) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < fireworkBoostGraceUntil.getOrDefault(uuid, 0L)) return;

        double horizontal = Math.hypot(
            event.getTo().getX() - event.getFrom().getX(),
            event.getTo().getZ() - event.getFrom().getZ());
        double compensation = anticheat.getTpsMonitor().getCompensationFactor();
        if (horizontal > maxHorizontalSpeed * compensation) {
            flag(player, 1.0, String.format("elytraHSpeed=%.2f", horizontal));
        }

        double dy = event.getTo().getY() - event.getFrom().getY();
        if (dy > maxSustainedClimbPerTick) {
            int streak = climbStreak.merge(uuid, 1, Integer::sum);
            if (streak >= requiredStreak) {
                flag(player, 1.0, String.format("sustainedClimbTicks=%d", streak));
                climbStreak.put(uuid, 0);
            }
        } else {
            climbStreak.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        fireworkBoostGraceUntil.remove(uuid);
        climbStreak.remove(uuid);
    }
}
