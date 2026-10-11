package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teleports a player back to safety when they fall below the world's
 * minimum height (the void). This prevents AntiKnockback hacks that
 * push players through the world floor, and also acts as a safety net
 * for players who fall into void via bugs.
 *
 * Uses a cooldown to avoid teleport-spam when a player is falling fast.
 */
public final class AntiVoidCheck extends Check implements Listener {

    private int offsetAboveMin;
    private final Map<UUID, Long> lastTeleport = new ConcurrentHashMap<>();
    private long cooldownMs;

    public AntiVoidCheck(AnticheatModule anticheat) {
        super(anticheat, "antivoid", "AntiVoid");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        offsetAboveMin = plugin.getConfig().getInt(path("offset-above-min"), 10);
        cooldownMs = plugin.getConfig().getLong(path("cooldown-ms"), 3000);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        int minHeight = player.getWorld().getMinHeight();
        if (event.getTo().getY() >= minHeight) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastTeleport.get(uuid);
        if (last != null && now - last < cooldownMs) return;
        lastTeleport.put(uuid, now);

        // Teleport back to world min-height + offset (safe zone just above void)
        var safeBlock = player.getWorld().getHighestBlockAt(player.getLocation());
        var safe = safeBlock.getLocation().add(0, 1, 0);
        // If above-ground lookup fails or lands on a hazardous block, fall back to minHeight + offset
        if (safe.getY() < minHeight || isHazardous(safeBlock.getType())) {
            safe = event.getTo().clone();
            safe.setY(minHeight + offsetAboveMin);
        }
        player.teleport(safe);
        flag(player, 1.0, String.format("y=%.1f below minHeight=%d", event.getTo().getY(), minHeight));
    }

    private boolean isHazardous(org.bukkit.Material m) {
        return m == org.bukkit.Material.WATER || m == org.bukkit.Material.LAVA
            || m.name().equals("BUBBLE_COLUMN") || m == org.bukkit.Material.FIRE
            || m == org.bukkit.Material.CAMPFIRE || m.name().equals("SOUL_CAMPFIRE");
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        lastTeleport.remove(event.getPlayer().getUniqueId());
    }
}
