package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects AntiHunger hacks that prevent the hunger bar from depleting
 * during activities that should consume it. Vanilla depletes hunger
 * from: sprinting, jumping, taking damage, and regeneration.
 *
 * Detection logic: a repeating task samples each online player every
 * 10 ticks (0.5s). If the player is actively sprinting or jumping (we
 * detect jumping via positive Y velocity while not on ground), we
 * increment activeTicks. If their food level stays at maximum while
 * activeTicks exceeds minActiveTicks, we increment stableTicks. When
 * stableTicks reaches requiredStableTicks, we flag.
 *
 * Exemptions: players in Creative/Spectator, players with a
 * saturation effect from a plugin, and the first 5 seconds after
 * respawn/eating are exempt.
 */
public final class AntiHungerCheck extends Check implements Listener {

    private final Map<UUID, Integer> lastFoodLevel = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> activeTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> stableTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> exemptUntil = new ConcurrentHashMap<>();
    private int minActiveTicks;
    private int requiredStableTicks;
    private BukkitTask monitorTask;

    public AntiHungerCheck(AnticheatModule anticheat) {
        super(anticheat, "antihunger", "AntiHunger");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minActiveTicks = plugin.getConfig().getInt(path("min-active-ticks"), 100); // 5 seconds of activity
        requiredStableTicks = plugin.getConfig().getInt(path("required-stable-ticks"), 60); // 3 seconds = 60 ticks at 20 TPS
    }

    public void start() {
        stop();
        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::monitor, 10L, 10L);
    }

    public void stop() {
        if (monitorTask != null) { monitorTask.cancel(); monitorTask = null; }
    }

    private void monitor() {
        if (!enabled) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (shouldSkip(player)) continue;
            if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;

            UUID uuid = player.getUniqueId();
            Long exempt = exemptUntil.get(uuid);
            if (exempt != null && System.currentTimeMillis() < exempt) {
                activeTicks.put(uuid, 0);
                stableTicks.put(uuid, 0);
                continue;
            }

            boolean isActive = player.isSprinting()
                || (!player.isOnGround() && player.getVelocity().getY() > 0.1); // jumping
            int food = player.getFoodLevel();

            if (isActive) {
                activeTicks.merge(uuid, 1, Integer::sum);
            } else {
                activeTicks.put(uuid, 0);
                stableTicks.put(uuid, 0);
                lastFoodLevel.put(uuid, food);
                continue;
            }

            int active = activeTicks.getOrDefault(uuid, 0);
            if (active < minActiveTicks) {
                lastFoodLevel.put(uuid, food);
                continue;
            }

            // Player has been active long enough - check if food is stuck at max
            Integer last = lastFoodLevel.get(uuid);
            lastFoodLevel.put(uuid, food);

            if (food >= 19 && last != null && last >= 19) {
                // Food has been at max for 2+ consecutive samples while active
                int stable = stableTicks.merge(uuid, 1, Integer::sum);
                if (stable >= requiredStableTicks / 10) { // convert ticks to samples (sample every 10 ticks)
                    flag(player, 1.0, String.format("food=%d activeTicks=%d stableSamples=%d", food, active, stable));
                    stableTicks.put(uuid, 0);
                    activeTicks.put(uuid, 0);
                }
            } else {
                stableTicks.put(uuid, 0);
            }
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        exemptUntil.put(uuid, System.currentTimeMillis() + 5000);
        lastFoodLevel.remove(uuid);
        stableTicks.put(uuid, 0);
        activeTicks.put(uuid, 0);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        exemptUntil.put(uuid, System.currentTimeMillis() + 5000);
        lastFoodLevel.remove(uuid);
        activeTicks.remove(uuid);
        stableTicks.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastFoodLevel.remove(uuid);
        activeTicks.remove(uuid);
        stableTicks.remove(uuid);
        exemptUntil.remove(uuid);
    }
}
