package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags landing from a significant fall without taking fall damage (NoFall hacks cancel the client-side flag). */
public final class NoFallCheck extends Check implements Listener {

    private final Map<UUID, Double> peakY = new ConcurrentHashMap<>();
    private double minFallDistance;

    public NoFallCheck(AnticheatModule anticheat) {
        super(anticheat, "nofall", "NoFall");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minFallDistance = ConfigUtil.getBoundedDouble(plugin, path("min-fall-distance"), 3.0, 2.0, 10.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        double y = player.getLocation().getY();
        double peak = peakY.getOrDefault(uuid, y);
        if (y > peak) peakY.put(uuid, y);

        if (player.isOnGround()) {
            double fallDepth = peak - y;
            if (fallDepth >= minFallDistance && player.getFallDistance() <= 0.1f
                    && !hasFallProtection(player)) {
                flag(player, 1.0, String.format("fall=%.1f, fallDistance=%.1f", fallDepth, player.getFallDistance()));
            }
            peakY.put(uuid, y);
        }
    }

    private boolean hasFallProtection(Player player) {
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)) return true;
        var boots = player.getInventory().getBoots();
        if (boots != null && boots.getType() != org.bukkit.Material.AIR
                && boots.containsEnchantment(org.bukkit.enchantments.Enchantment.FEATHER_FALLING)) {
            return true;
        }
        Material atFeet = player.getLocation().getBlock().getType();
        if (atFeet == Material.WATER || atFeet == Material.LAVA
                || atFeet == Material.COBWEB || atFeet == Material.SLIME_BLOCK
                || atFeet == Material.HONEY_BLOCK || atFeet == Material.POWDER_SNOW
                || atFeet == Material.LADDER || atFeet == Material.VINE
                || atFeet == Material.WEEPING_VINES || atFeet == Material.TWISTING_VINES
                || atFeet == Material.SCAFFOLDING) {
            return true;
        }
        Material below = player.getLocation().clone().subtract(0, 0.5, 0).getBlock().getType();
        if (below == Material.SLIME_BLOCK || below == Material.HONEY_BLOCK
                || below == Material.HAY_BLOCK || below.name().endsWith("_BED")) {
            return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player) {
            peakY.put(player.getUniqueId(), player.getLocation().getY());
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        peakY.put(event.getPlayer().getUniqueId(), event.getTo().getY());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        peakY.remove(event.getPlayer().getUniqueId());
    }
}
