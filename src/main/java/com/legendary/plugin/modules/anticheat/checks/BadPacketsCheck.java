package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

/**
 * Detects impossible player states that should never occur in legitimate gameplay:
 * - Toggling flight ON while in Survival/Adventure without permission (Flight hack)
 * - Switching to Creative/Spectator without a legitimate cause (GameMode exploit)
 *
 * Note: We only flag the toggle-flight event, not the gamemode event, since
 * gamemode changes can be caused by other plugins (minigame frameworks etc.).
 * The flight toggle in Survival is always illegitimate unless the player has
 * the allow-flight server property or a plugin that grants it explicitly.
 */
public final class BadPacketsCheck extends Check implements Listener {

    public BadPacketsCheck(AnticheatModule anticheat) {
        super(anticheat, "badpackets", "BadPackets");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (!event.isFlying()) return; // toggling off is fine
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (player.getAllowFlight()) return; // server or plugin already granted flight

        flag(player, 3.0, "flight-toggle in " + gm.name());
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGamemodeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        GameMode to = event.getNewGameMode();
        // Only suspicious if switching TO Creative/Spectator without OP or permission
        if (to != GameMode.CREATIVE && to != GameMode.SPECTATOR) return;
        if (player.isOp() || player.hasPermission("minecraft.command.gamemode")) return;

        flag(player, 4.0, "gamemode-change to " + to.name() + " without permission");
        event.setCancelled(true);
    }
}
