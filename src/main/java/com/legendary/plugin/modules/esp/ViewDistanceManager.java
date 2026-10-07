package com.legendary.plugin.modules.esp;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Caps each player's client-requested view distance so they cannot see
 * (and thus cannot ESP-scan) chunks far beyond what is needed to play.
 * Ported from AntiESPUltimate.ViewDistanceModule.
 */
public final class ViewDistanceManager implements Listener {

    private final LegendaryPlugin plugin;
    private int maxViewDistance;

    public ViewDistanceManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        this.maxViewDistance = com.legendary.plugin.core.ConfigUtil.getBoundedInt(
            plugin, "esp.view-distance.max-view-distance", 8, 2, 32);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        apply(event.getPlayer());
    }

    private void apply(Player player) {
        if (player.hasPermission("legendary.esp.bypass")) return;
        try {
            // Paper-specific API: caps what the client is sent regardless of the
            // player's own client-side render-distance slider. Defensively clamp the final
            // value ourselves (min 2, matching World#setViewDistance's documented floor)
            // instead of trusting getSendViewDistance()'s current value blindly - the same
            // class of "pass an unvalidated number into a strict Bukkit setter" mistake broke
            // WorldOptimizer in production (2026-09-19 incident); this is the same setter
            // pattern, so it gets the same defensive treatment even though its exact valid
            // range isn't independently confirmed here.
            int current = player.getSendViewDistance();
            int target = current < 0 ? maxViewDistance : Math.min(maxViewDistance, current);
            player.setSendViewDistance(Math.max(2, target));
        } catch (Throwable ignored) {
            // Older/foreign server jars without this Paper API - fail open, no crash.
        }
    }
}
