package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects FastEat / FastDrink hacks that consume food or potions faster
 * than the vanilla use-duration allows.
 *
 * Vanilla use durations (in ms):
 *   Food (most):  1600 ms
 *   Milk bucket:  1600 ms
 *   Potion:        800 ms (instant potions have 0 ms - we skip those)
 *   Chorus fruit:  1000 ms
 *
 * We record when the player started using an item via the held-item
 * tracking and compare against the consume event timestamp. Discrepancies
 * below minEatMs are flagged.
 */
public final class FastEatCheck extends Check implements Listener {

    private static final int VANILLA_FOOD_MS    = 1600;
    private static final int VANILLA_POTION_MS  = 800;
    private static final int VANILLA_CHORUS_MS  = 1000;

    private final Map<UUID, Long> useStartTime = new ConcurrentHashMap<>();
    private final Map<UUID, Material> useItem = new ConcurrentHashMap<>();
    private int requiredStreak;
    private final Map<UUID, Integer> streak = new ConcurrentHashMap<>();

    public FastEatCheck(AnticheatModule anticheat) {
        super(anticheat, "fasteat", "FastEat");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        requiredStreak = plugin.getConfig().getInt(path("required-streak"), 2);
    }

    @EventHandler
    public void onSlotSwitch(org.bukkit.event.player.PlayerItemHeldEvent event) {
        // Clear tracking when player switches hotbar slots (vanilla cancels any in-progress eat)
        useStartTime.remove(event.getPlayer().getUniqueId());
        useItem.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (isConsumable(item)) {
            useStartTime.put(player.getUniqueId(), System.currentTimeMillis());
            useItem.put(player.getUniqueId(), item.getType());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        Long start = useStartTime.remove(uuid);
        useItem.remove(uuid);
        if (start == null) return;

        long elapsed = System.currentTimeMillis() - start;
        int expectedMs = expectedDuration(event.getItem());
        if (expectedMs <= 0) return; // instant potions - skip

        if (elapsed < expectedMs - 200) { // 200 ms leniency for ping
            int s = streak.merge(uuid, 1, Integer::sum);
            if (s >= requiredStreak) {
                flag(player, 1.5, String.format("consumed %s in %dms (min %dms)", event.getItem().getType(), elapsed, expectedMs));
                streak.put(uuid, 0);
            }
        } else {
            streak.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        useStartTime.remove(uuid);
        useItem.remove(uuid);
        streak.remove(uuid);
    }

    private boolean isConsumable(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        return item.getType().isEdible()
            || item.getType() == Material.POTION
            || item.getType() == Material.MILK_BUCKET;
    }

    private int expectedDuration(ItemStack item) {
        if (item == null) return 0;
        if (item.getType() == Material.CHORUS_FRUIT) return VANILLA_CHORUS_MS;
        if (item.getType() == Material.POTION) {
            // Instant potions (healing, harming) have 0 duration
            if (item.getItemMeta() instanceof PotionMeta meta) {
                PotionType type = meta.getBasePotionType();
                if (type != null && (type.name().equals("HEALING") || type.name().equals("HARMING") || type.name().equals("INSTANT_HEAL") || type.name().equals("INSTANT_DAMAGE"))) return 0;
            }
            return VANILLA_POTION_MS;
        }
        if (item.getType().isEdible()) return VANILLA_FOOD_MS;
        return 0;
    }
}
