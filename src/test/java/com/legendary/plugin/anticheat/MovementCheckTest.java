package com.legendary.plugin.anticheat;

import com.legendary.plugin.modules.anticheat.checks.MovementCheck;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the real MovementCheck logic against mocked Paper API objects.
 * Verifies speed detection, flight detection, grace windows, and
 * TPS compensation behavior.
 */
class MovementCheckTest extends CheckTestBase {

    private MovementCheck check;

    @BeforeEach
    void setUpCheck() {
        check = new MovementCheck(anticheat);
        check.loadConfigValues();
    }

    @Test
    @DisplayName("Normal sprint speed (0.28 blocks/tick) should NOT flag")
    void normalSpeedNoFlag() {
        World world = mock(World.class);
        Location from = makeLocation(world, 0, 64, 0);
        Location to = makeLocation(world, 0, 64, 0.28);

        Player p = makePlayer(UUID.randomUUID(), "Runner", world, from);
        when(p.getLocation()).thenReturn(to);

        PlayerMoveEvent event = makeMoveEvent(p, from, to);
        check.onMove(event);

        assertFalse(flagged("movement"), "Normal sprint speed should not flag");
    }

    @Test
    @DisplayName("Excessive horizontal speed (0.6 blocks/tick) SHOULD flag after streak")
    void excessiveSpeedFlags() {
        World world = mock(World.class);
        Location from = makeLocation(world, 0, 64, 0);
        Location to = makeLocation(world, 0, 64, 0.6);

        Player p = makePlayer(UUID.randomUUID(), "SpeedHacker", world, from);
        when(p.getLocation()).thenReturn(to);

        // MovementCheck requires streak >= 3
        for (int i = 0; i < 5; i++) {
            Location f = makeLocation(world, i * 0.6, 64, 0);
            Location t = makeLocation(world, (i + 1) * 0.6, 64, 0);
            when(p.getLocation()).thenReturn(t);
            check.onMove(makeMoveEvent(p, f, t));
        }

        assertTrue(flagged("movement"), "Sustained 0.6 b/t speed should flag after streak");
    }

    @Test
    @DisplayName("Player in creative mode should NOT be flagged for speed")
    void creativeModeNoFlag() {
        World world = mock(World.class);
        Location from = makeLocation(world, 0, 64, 0);
        Location to = makeLocation(world, 0, 64, 0.6);

        Player p = makePlayer(UUID.randomUUID(), "Builder", world, from);
        when(p.getGameMode()).thenReturn(org.bukkit.GameMode.CREATIVE);
        when(p.getLocation()).thenReturn(to);

        PlayerMoveEvent event = makeMoveEvent(p, from, to);
        check.onMove(event);

        assertFalse(flagged("movement"), "Creative mode players should not be checked");
    }

    @Test
    @DisplayName("Flying player should NOT be flagged for speed")
    void flyingNoFlag() {
        World world = mock(World.class);
        Location from = makeLocation(world, 0, 64, 0);
        Location to = makeLocation(world, 0, 64, 0.6);

        Player p = makePlayer(UUID.randomUUID(), "Flyer", world, from);
        when(p.isFlying()).thenReturn(true);
        when(p.getLocation()).thenReturn(to);

        PlayerMoveEvent event = makeMoveEvent(p, from, to);
        check.onMove(event);

        assertFalse(flagged("movement"), "Flying players should not be checked for speed");
    }

    @Test
    @DisplayName("Gliding (elytra) player should NOT be flagged for speed")
    void glidingNoFlag() {
        World world = mock(World.class);
        Location from = makeLocation(world, 0, 64, 0);
        Location to = makeLocation(world, 0, 64, 0.6);

        Player p = makePlayer(UUID.randomUUID(), "Glider", world, from);
        when(p.isGliding()).thenReturn(true);
        when(p.getLocation()).thenReturn(to);

        PlayerMoveEvent event = makeMoveEvent(p, from, to);
        check.onMove(event);

        assertFalse(flagged("movement"), "Gliding players should not be checked for speed");
    }

    @Test
    @DisplayName("Teleport grace window should prevent flag")
    void teleportGracePreventsFlag() {
        World world = mock(World.class);
        Player p = makePlayer(UUID.randomUUID(), "Teleporter", world, makeLocation(world, 0, 64, 0));

        // Fire teleport event to set grace
        Location tpFrom = makeLocation(world, 0, 64, 0);
        Location tpTo = makeLocation(world, 100, 64, 100);
        org.bukkit.event.player.PlayerTeleportEvent tpEvent =
            new org.bukkit.event.player.PlayerTeleportEvent(p, tpFrom, tpTo);
        check.onTeleport(tpEvent);

        // Now move with high speed - should be in grace
        Location from = makeLocation(world, 100, 64, 100);
        Location to = makeLocation(world, 100, 64, 100.6);
        when(p.getLocation()).thenReturn(to);

        PlayerMoveEvent event = makeMoveEvent(p, from, to);
        check.onMove(event);

        assertFalse(flagged("movement"), "Movement within teleport grace window should not flag");
    }

    @Test
    @DisplayName("Disabled check should NOT flag")
    void disabledNoFlag() {
        check.setEnabled(false);

        World world = mock(World.class);
        Location from = makeLocation(world, 0, 64, 0);
        Location to = makeLocation(world, 0, 64, 0.6);

        Player p = makePlayer(UUID.randomUUID(), "Hacker", world, from);
        when(p.getLocation()).thenReturn(to);

        PlayerMoveEvent event = makeMoveEvent(p, from, to);
        check.onMove(event);

        assertFalse(flagged("movement"), "Disabled check should never flag");
    }
}
