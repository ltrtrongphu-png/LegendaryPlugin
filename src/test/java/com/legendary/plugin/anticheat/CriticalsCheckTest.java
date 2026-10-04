package com.legendary.plugin.anticheat;

import com.legendary.plugin.modules.anticheat.checks.CriticalsCheck;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the real CriticalsCheck logic: a critical hit while standing on
 * the ground should flag; a critical hit while airborne should not.
 */
class CriticalsCheckTest extends CheckTestBase {

    private CriticalsCheck check;

    @BeforeEach
    void setUpCheck() {
        check = new CriticalsCheck(anticheat);
        check.loadConfigValues();
    }

    @Test
    @DisplayName("Critical hit while grounded SHOULD flag")
    void groundedCritFlags() {
        World world = mock(World.class);
        Player attacker = makePlayer(UUID.randomUUID(), "CritHacker", world, makeLocation(world, 0, 64, 0));
        when(attacker.isOnGround()).thenReturn(true);

        LivingEntity target = mock(LivingEntity.class);
        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target, true);

        check.onAttack(event);

        assertTrue(flagged("criticals"), "Critical hit while on ground should flag");
    }

    @Test
    @DisplayName("Critical hit while airborne should NOT flag")
    void airborneCritNoFlag() {
        World world = mock(World.class);
        Player attacker = makePlayer(UUID.randomUUID(), "Jumper", world, makeLocation(world, 0, 64, 0));
        when(attacker.isOnGround()).thenReturn(false);

        LivingEntity target = mock(LivingEntity.class);
        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target, true);

        check.onAttack(event);

        assertFalse(flagged("criticals"), "Airborne critical hit is legitimate (vanilla)");
    }

    @Test
    @DisplayName("Non-critical hit while grounded should NOT flag")
    void nonCritNoFlag() {
        World world = mock(World.class);
        Player attacker = makePlayer(UUID.randomUUID(), "NormalHit", world, makeLocation(world, 0, 64, 0));
        when(attacker.isOnGround()).thenReturn(true);

        LivingEntity target = mock(LivingEntity.class);
        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target, false);

        check.onAttack(event);

        assertFalse(flagged("criticals"), "Non-critical hit while grounded is normal");
    }

    @Test
    @DisplayName("Critical hit while in water should NOT flag (vanilla allows this)")
    void waterCritNoFlag() {
        World world = mock(World.class);
        Player attacker = makePlayer(UUID.randomUUID(), "Swimmer", world, makeLocation(world, 0, 64, 0));
        when(attacker.isOnGround()).thenReturn(true);
        when(attacker.isInWater()).thenReturn(true);

        LivingEntity target = mock(LivingEntity.class);
        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target, true);

        check.onAttack(event);

        assertFalse(flagged("criticals"), "Critical hit in water is legitimate (vanilla)");
    }

    @Test
    @DisplayName("Disabled check should NOT flag")
    void disabledNoFlag() {
        check.setEnabled(false);

        World world = mock(World.class);
        Player attacker = makePlayer(UUID.randomUUID(), "Hacker", world, makeLocation(world, 0, 64, 0));
        when(attacker.isOnGround()).thenReturn(true);

        LivingEntity target = mock(LivingEntity.class);
        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target, true);

        check.onAttack(event);

        assertFalse(flagged("criticals"), "Disabled check should never flag");
    }
}
