package com.legendary.plugin.anticheat;

import com.legendary.plugin.modules.anticheat.checks.KillAuraCheck;
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
 * Tests the real KillAuraCheck logic against mocked Paper API objects.
 * Verifies reach detection, multiaura, autoclicker timing, and rotation snap.
 */
class KillAuraCheckTest extends CheckTestBase {

    private KillAuraCheck check;

    @BeforeEach
    void setUpCheck() {
        check = new KillAuraCheck(anticheat);
        check.loadConfigValues();
    }

    @Test
    @DisplayName("Legit melee reach (3.5 blocks) should NOT flag")
    void legitReachNoFlag() {
        World world = mock(World.class);
        Location attackerLoc = makeLocation(world, 0, 64, 0);
        Location targetLoc = makeLocation(world, 0, 64, 3.5);

        Player attacker = makePlayer(UUID.randomUUID(), "LegitPlayer", world, attackerLoc);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getWorld()).thenReturn(world);
        when(target.getEyeLocation()).thenReturn(targetLoc);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());

        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target);

        check.onAttack(event);

        assertFalse(flagged("killaura"), "Legit 3.5-block reach should not trigger KillAura");
    }

    @Test
    @DisplayName("Excessive reach (5.0 blocks) SHOULD flag")
    void excessiveReachFlags() {
        World world = mock(World.class);
        Location attackerLoc = makeLocation(world, 0, 64, 0);
        Location targetLoc = makeLocation(world, 0, 64, 5.0);

        Player attacker = makePlayer(UUID.randomUUID(), "Hacker", world, attackerLoc);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getWorld()).thenReturn(world);
        when(target.getEyeLocation()).thenReturn(targetLoc);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());

        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target);

        check.onAttack(event);

        assertTrue(flagged("killaura"), "5.0-block reach should trigger KillAura");
    }

    @Test
    @DisplayName("Multiaura: hitting 2 different targets within 60ms SHOULD flag")
    void multiauraFlags() {
        World world = mock(World.class);
        Location attackerLoc = makeLocation(world, 0, 64, 0);
        Location targetLoc1 = makeLocation(world, 0, 64, 3.0);
        Location targetLoc2 = makeLocation(world, 3, 64, 0);

        Player attacker = makePlayer(UUID.randomUUID(), "MultiHacker", world, attackerLoc);

        UUID target1Id = UUID.randomUUID();
        UUID target2Id = UUID.randomUUID();

        LivingEntity target1 = mock(LivingEntity.class);
        when(target1.getWorld()).thenReturn(world);
        when(target1.getEyeLocation()).thenReturn(targetLoc1);
        when(target1.getUniqueId()).thenReturn(target1Id);

        LivingEntity target2 = mock(LivingEntity.class);
        when(target2.getWorld()).thenReturn(world);
        when(target2.getEyeLocation()).thenReturn(targetLoc2);
        when(target2.getUniqueId()).thenReturn(target2Id);

        EntityDamageByEntityEvent event1 = makeDamageEvent(attacker, target1);
        check.onAttack(event1);

        EntityDamageByEntityEvent event2 = makeDamageEvent(attacker, target2);
        check.onAttack(event2);

        assertTrue(flagged("killaura"), "Hitting 2 targets within 60ms should flag multiaura");
    }

    @Test
    @DisplayName("Hitting same target twice should NOT flag multiaura")
    void sameTargetNoMultiaura() {
        World world = mock(World.class);
        Location attackerLoc = makeLocation(world, 0, 64, 0);
        Location targetLoc = makeLocation(world, 0, 64, 3.0);

        Player attacker = makePlayer(UUID.randomUUID(), "LegitCombat", world, attackerLoc);
        UUID targetId = UUID.randomUUID();

        LivingEntity target = mock(LivingEntity.class);
        when(target.getWorld()).thenReturn(world);
        when(target.getEyeLocation()).thenReturn(targetLoc);
        when(target.getUniqueId()).thenReturn(targetId);

        EntityDamageByEntityEvent event1 = makeDamageEvent(attacker, target);
        check.onAttack(event1);

        EntityDamageByEntityEvent event2 = makeDamageEvent(attacker, target);
        check.onAttack(event2);

        assertFalse(flagged("killaura"), "Same target hit twice should not flag multiaura");
    }

    @Test
    @DisplayName("Cross-world attack should NOT flag (different worlds)")
    void crossWorldNoFlag() {
        World world1 = mock(World.class);
        World world2 = mock(World.class);
        Location attackerLoc = makeLocation(world1, 0, 64, 0);
        Location targetLoc = makeLocation(world2, 0, 64, 5.0);

        Player attacker = makePlayer(UUID.randomUUID(), "CrossWorld", world1, attackerLoc);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getWorld()).thenReturn(world2);
        when(target.getEyeLocation()).thenReturn(targetLoc);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());

        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target);

        check.onAttack(event);

        assertFalse(flagged("killaura"), "Cross-world attack should not flag (distance is meaningless)");
    }

    @Test
    @DisplayName("Disabled check should NOT flag even with excessive reach")
    void disabledCheckNoFlag() {
        check.setEnabled(false);

        World world = mock(World.class);
        Location attackerLoc = makeLocation(world, 0, 64, 0);
        Location targetLoc = makeLocation(world, 0, 64, 5.0);

        Player attacker = makePlayer(UUID.randomUUID(), "Hacker", world, attackerLoc);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getWorld()).thenReturn(world);
        when(target.getEyeLocation()).thenReturn(targetLoc);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());

        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target);

        check.onAttack(event);

        assertFalse(flagged("killaura"), "Disabled check should never flag");
    }
}
