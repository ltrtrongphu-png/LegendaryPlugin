package com.legendary.plugin.anticheat;

import com.legendary.plugin.modules.anticheat.checks.NoFallCheck;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoFallCheckTest extends CheckTestBase {

    private NoFallCheck check;

    @BeforeEach
    void setUpCheck() {
        check = new NoFallCheck(anticheat);
        check.loadConfigValues();
    }

    private boolean canLoadBukkitRegistries() {
        try {
            Class.forName("org.bukkit.potion.PotionEffectType", false, Thread.currentThread().getContextClassLoader());
            org.bukkit.potion.PotionEffectType.SLOW_FALLING.getClass();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Test
    @DisplayName("Fall 5 blocks onto stone with fallDistance=0 SHOULD flag")
    void noFallHackFlags() {
        assumeTrue(canLoadBukkitRegistries(), "PotionEffectType requires a Bukkit server");

        World world = mock(World.class);
        Location groundLoc = makeLocation(world, 0, 65, 0);
        Location highLoc = makeLocation(world, 0, 70, 0);
        Player p = makePlayer(UUID.randomUUID(), "NoFallHacker", world, groundLoc);

        when(p.getLocation()).thenReturn(groundLoc);
        when(p.isOnGround()).thenReturn(true);
        check.onMove(makeMoveEvent(p, groundLoc, groundLoc));

        when(p.getLocation()).thenReturn(highLoc);
        when(p.isOnGround()).thenReturn(false);
        check.onMove(makeMoveEvent(p, groundLoc, highLoc));

        when(p.getLocation()).thenReturn(groundLoc);
        when(p.isOnGround()).thenReturn(true);
        when(p.getFallDistance()).thenReturn(0f);

        check.onMove(makeMoveEvent(p, highLoc, groundLoc));

        assertTrue(flagged("nofall"), "5-block fall with fallDistance=0 and no protection should flag");
    }

    @Test
    @DisplayName("Fall 5 blocks into water should NOT flag")
    void waterLandingNoFlag() {
        assumeTrue(canLoadBukkitRegistries(), "PotionEffectType requires a Bukkit server");

        World world = mock(World.class);
        Location groundLoc = makeLocation(world, 0, 65, 0);
        Location highLoc = makeLocation(world, 0, 70, 0);
        Player p = makePlayer(UUID.randomUUID(), "Diver", world, groundLoc);

        when(p.getLocation()).thenReturn(groundLoc);
        when(p.isOnGround()).thenReturn(true);
        check.onMove(makeMoveEvent(p, groundLoc, groundLoc));

        when(p.getLocation()).thenReturn(highLoc);
        when(p.isOnGround()).thenReturn(false);
        check.onMove(makeMoveEvent(p, groundLoc, highLoc));

        Location waterLoc = makeLocation(world, 0, 65, 0);
        Block waterBlock = mock(Block.class);
        when(waterBlock.getType()).thenReturn(org.bukkit.Material.WATER);
        when(waterLoc.getBlock()).thenReturn(waterBlock);

        when(p.getLocation()).thenReturn(waterLoc);
        when(p.isOnGround()).thenReturn(true);
        when(p.getFallDistance()).thenReturn(0f);

        check.onMove(makeMoveEvent(p, highLoc, waterLoc));

        assertFalse(flagged("nofall"), "Falling into water should not flag NoFall");
    }

    @Test
    @DisplayName("Fall 2 blocks (below threshold) should NOT flag")
    void shortFallNoFlag() {
        assumeTrue(canLoadBukkitRegistries(), "PotionEffectType requires a Bukkit server");

        World world = mock(World.class);
        Location groundLoc = makeLocation(world, 0, 65, 0);
        Location highLoc = makeLocation(world, 0, 67, 0);
        Player p = makePlayer(UUID.randomUUID(), "Jumper", world, groundLoc);

        when(p.getLocation()).thenReturn(groundLoc);
        when(p.isOnGround()).thenReturn(true);
        check.onMove(makeMoveEvent(p, groundLoc, groundLoc));

        when(p.getLocation()).thenReturn(highLoc);
        when(p.isOnGround()).thenReturn(false);
        check.onMove(makeMoveEvent(p, groundLoc, highLoc));

        when(p.getLocation()).thenReturn(groundLoc);
        when(p.isOnGround()).thenReturn(true);
        when(p.getFallDistance()).thenReturn(0f);

        check.onMove(makeMoveEvent(p, highLoc, groundLoc));

        assertFalse(flagged("nofall"), "2-block fall is below min-fall-distance threshold (3.0)");
    }

    @Test
    @DisplayName("Fall 5 blocks onto slime block should NOT flag")
    void slimeLandingNoFlag() {
        assumeTrue(canLoadBukkitRegistries(), "PotionEffectType requires a Bukkit server");

        World world = mock(World.class);
        Location groundLoc = makeLocation(world, 0, 65, 0);
        Location highLoc = makeLocation(world, 0, 70, 0);
        Player p = makePlayer(UUID.randomUUID(), "Bouncer", world, groundLoc);

        when(p.getLocation()).thenReturn(groundLoc);
        when(p.isOnGround()).thenReturn(true);
        check.onMove(makeMoveEvent(p, groundLoc, groundLoc));

        when(p.getLocation()).thenReturn(highLoc);
        when(p.isOnGround()).thenReturn(false);
        check.onMove(makeMoveEvent(p, groundLoc, highLoc));

        Location slimeLoc = makeLocation(world, 0, 65, 0);
        Block slimeBlock = mock(Block.class);
        when(slimeBlock.getType()).thenReturn(org.bukkit.Material.SLIME_BLOCK);
        when(slimeLoc.getBlock()).thenReturn(slimeBlock);
        when(slimeLoc.clone()).thenReturn(slimeLoc);
        when(slimeLoc.subtract(0, 0.5, 0)).thenReturn(slimeLoc);

        when(p.getLocation()).thenReturn(slimeLoc);
        when(p.isOnGround()).thenReturn(true);
        when(p.getFallDistance()).thenReturn(0f);

        check.onMove(makeMoveEvent(p, highLoc, slimeLoc));

        assertFalse(flagged("nofall"), "Falling onto slime block should not flag NoFall");
    }

    @Test
    @DisplayName("Disabled check should NOT flag")
    void disabledNoFlag() {
        assumeTrue(canLoadBukkitRegistries(), "PotionEffectType requires a Bukkit server");

        check.setEnabled(false);

        World world = mock(World.class);
        Location startLoc = makeLocation(world, 0, 70, 0);
        Player p = makePlayer(UUID.randomUUID(), "Hacker", world, startLoc);

        Location fallLoc = makeLocation(world, 0, 65, 0);
        when(p.getLocation()).thenReturn(fallLoc);
        when(p.isOnGround()).thenReturn(true);
        when(p.getFallDistance()).thenReturn(0f);

        check.onMove(makeMoveEvent(p, startLoc, fallLoc));

        assertFalse(flagged("nofall"), "Disabled check should never flag");
    }
}
