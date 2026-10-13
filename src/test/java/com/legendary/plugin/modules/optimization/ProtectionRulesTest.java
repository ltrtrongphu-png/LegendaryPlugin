package com.legendary.plugin.modules.optimization;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Wither;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProtectionRulesTest {

    @Test
    void protectsBossesRaidersAndMobsRecentlyTargetingPlayers() {
        assertTrue(ProtectionRules.isProtectedMob(mock(Wither.class)));
        assertTrue(ProtectionRules.isProtectedMob(mock(Raider.class)));

        Monster monster = mock(Monster.class);
        when(monster.getTarget()).thenReturn(mock(Player.class));
        assertTrue(ProtectionRules.isProtectedMob(monster));
    }

    @Test
    void protectsFreshAndValuableDroppedItemsUnlessValuablesAreExplicitlyAllowed() {
        Item freshItem = mock(Item.class);
        when(freshItem.getTicksLived()).thenReturn(200);
        assertTrue(ProtectionRules.isProtectedItem(freshItem, true, 2_400, false));

        Item valuableItem = mock(Item.class);
        when(valuableItem.getTicksLived()).thenReturn(2_400);
        ItemStack stack = mock(ItemStack.class);
        when(valuableItem.getItemStack()).thenReturn(stack);
        when(stack.getType()).thenReturn(Material.DIAMOND);
        assertTrue(ProtectionRules.isProtectedItem(valuableItem, true, 2_400, false));
        assertFalse(ProtectionRules.isProtectedItem(valuableItem, false, 2_400, true));
    }
}
