package com.legendary.plugin.anticheat;

import com.legendary.plugin.modules.anticheat.checks.NoSwingCheck;
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
 * Tests the real NoSwingCheck logic: hitting an entity without a recent
 * arm-swing animation should flag; hitting after a recent swing should not.
 */
class NoSwingCheckTest extends CheckTestBase {

    private NoSwingCheck check;

    @BeforeEach
    void setUpCheck() {
        check = new NoSwingCheck(anticheat);
        check.loadConfigValues();
    }

    @Test
    @DisplayName("Attack with recent swing (within 200ms) should NOT flag")
    void swingThenAttackNoFlag() {
        World world = mock(World.class);
        Player attacker = makePlayer(UUID.randomUUID(), "Fighter", world, makeLocation(world, 0, 64, 0));

        // Fire swing event first
        check.onSwing(makeSwingEvent(attacker));

        // Then attack immediately
        LivingEntity target = mock(LivingEntity.class);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDamageByEntityEvent attackEvent = makeDamageEvent(attacker, target);
        check.onAttack(attackEvent);

        assertFalse(flagged("noswing"), "Attack immediately after swing should not flag");
    }

    @Test
    @DisplayName("Attack without any prior swing SHOULD flag")
    void noSwingFlags() {
        World world = mock(World.class);
        Player attacker = makePlayer(UUID.randomUUID(), "NoSwingHacker", world, makeLocation(world, 0, 64, 0));

        LivingEntity target = mock(LivingEntity.class);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target);
        check.onAttack(event);

        assertTrue(flagged("noswing"), "Attack without any swing should flag NoSwing");
    }

    @Test
    @DisplayName("Non-player damager should NOT trigger NoSwing check")
    void nonPlayerDamagerNoFlag() {
        World world = mock(World.class);
        LivingEntity mobAttacker = mock(LivingEntity.class);
        when(mobAttacker.getUniqueId()).thenReturn(UUID.randomUUID());

        LivingEntity target = mock(LivingEntity.class);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDamageByEntityEvent event = makeDamageEvent(mobAttacker, target);
        check.onAttack(event);

        assertFalse(flagged("noswing"), "Non-player attacker should not trigger NoSwing");
    }

    @Test
    @DisplayName("Disabled check should NOT flag even without swing")
    void disabledNoFlag() {
        check.setEnabled(false);

        World world = mock(World.class);
        Player attacker = makePlayer(UUID.randomUUID(), "Hacker", world, makeLocation(world, 0, 64, 0));

        LivingEntity target = mock(LivingEntity.class);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDamageByEntityEvent event = makeDamageEvent(attacker, target);
        check.onAttack(event);

        assertFalse(flagged("noswing"), "Disabled check should never flag");
    }
}
