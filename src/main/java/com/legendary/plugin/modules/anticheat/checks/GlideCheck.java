package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.GameMode;
import org.bukkit.Material;
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
 * Detects Glide hacks that make the player fall at a reduced rate
 * without an elytra, Slow Falling potion, or legitimate soft-landing
 * surface. Vanilla terminal velocity is ~3.92 blocks/tick downward;
 * a Glide hack typically caps downward speed at 0.1-0.4 blocks/tick.
 *
 * Upgraded with:
 * - Cobweb/ladder/vine/scaffolding exemption: these legitimately slow
 *   the player and shouldn't false-positive.
 * - Levitation potion exemption.
 * - Cumulative fall speed scaling: the longer the player falls, the
 *   higher the minimum expected speed. After 10 blocks the threshold
 *   rises to 0.6, after 20 blocks to 1.5, etc.
 * - TPS compensation so lag doesn't produce false positives.
 * - Teleport grace window.
 */
public final class GlideCheck extends Check implements Listener {

    private double baseMinFallSpeed;
    private int requiredStreak;
    private final Map<UUID, Integer> glideStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Double> totalFallDistance = new ConcurrentHashMap<>();
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();

    public GlideCheck(AnticheatModule anticheat) {
        super(anticheat, "glide", "Glide");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        baseMinFallSpeed = plugin.getConfig().getDouble(path("min-fall-speed"), 0.3);
        requiredStreak = plugin.getConfig().getInt(path("required-streak"), 8);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 500);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isGliding() || player.isFlying() || player.isOnGround()
            || player.isInWater() || player.isInsideVehicle()) return;
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)) return;
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) return;
        if (player.getInventory().getChestplate() != null
            && player.getInventory().getChestplate().getType().name().endsWith("ELYTRA")) return;

        UUID uuid = player.getUniqueId();
        Long grace = graceUntil.get(uuid);
        if (grace != null && System.currentTimeMillis() < grace) return;

        // Check for soft-landing blocks at feet
        Material atFeet = event.getTo().getBlock().getType();
        if (atFeet == Material.COBWEB || atFeet == Material.LADDER
            || atFeet == Material.VINE || atFeet == Material.SCAFFOLDING
            || atFeet.name().startsWith("WEEPING_VINES")
            || atFeet.name().startsWith("TWISTING_VINES")) {
            glideStreak.put(uuid, 0);
            totalFallDistance.put(uuid, 0.0);
            return;
        }

        double dy = event.getTo().getY() - event.getFrom().getY();

        if (dy < -0.01) {
            double fallDist = totalFallDistance.merge(uuid, -dy, Double::sum);
            // Scale minimum expected speed with fall distance
            double expectedMin = baseMinFallSpeed;
            if (fallDist > 10.0) expectedMin = 0.6;
            if (fallDist > 20.0) expectedMin = 1.5;
            if (fallDist > 30.0) expectedMin = 2.5;
            // TPS compensation
            double tpsFactor = anticheat.getTpsMonitor().getCompensationFactor();
            expectedMin /= tpsFactor;

            if (Math.abs(dy) < expectedMin) {
                int s = glideStreak.merge(uuid, 1, Integer::sum);
                if (s >= requiredStreak) {
                    flag(player, 1.5, String.format("fallSpeed=%.3f (min=%.3f) fallDist=%.1f streak=%d",
                        Math.abs(dy), expectedMin, fallDist, s));
                    glideStreak.put(uuid, 0);
                }
            } else {
                glideStreak.put(uuid, 0);
            }
        } else {
            glideStreak.put(uuid, 0);
            totalFallDistance.put(uuid, 0.0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        glideStreak.remove(uuid);
        totalFallDistance.remove(uuid);
        graceUntil.remove(uuid);
    }
}
