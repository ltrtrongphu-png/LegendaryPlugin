package com.legendary.plugin.modules.antiafkbot;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AntiAfkBotManagerTest {

    @Test
    void exemptsPlayersRidingMovingVehiclesFromIdleKicks() {
        AntiAfkBotManager manager = new AntiAfkBotManager(null);
        Player player = mock(Player.class);
        when(player.isInsideVehicle()).thenReturn(true);

        assertTrue(manager.isExemptFromKick(player));
    }
}
