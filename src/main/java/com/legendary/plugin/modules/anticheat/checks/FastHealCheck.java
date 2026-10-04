package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FastHeal: detects clients that accelerate health regeneration beyond
 * vanilla limits. Vanilla regen rates: 1 HP per 50 ticks (2.5s) with full
 * hunger, or 1 HP per 80 ticks (4s) with saturation. Hack clients bypass
 * this to heal instantly or at 2x+ rate. Detection: track health gains
 * over a 10-second window. If the total HP gained exceeds the vanilla
 * maximum for that period, flag. Exemptions: potion effects (Regeneration),
 * respawn, and natural regen with golden apple.
 */
public final class FastHealCheck extends Check implements Listener {

    private final Map<UUID, Double> healedInWindow = new ConcurrentHashMap<>();
    private final Map<UUID, Long> windowStart = new ConcurrentHashMap<>();
    private final Map<UUID, Long> exemptUntil = new ConcurrentHashMap<>();
    private double maxVanillaHpPerWindow;
    private long windowMs;
    private BukkitTask monitorTask;

    public FastHealCheck(AnticheatModule anticheat) {
        super(anticheat, "fastheal", "FastHeal");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        windowMs = plugin.getConfig().getLong(path("window-ms"), 10000);
        maxVanillaHpPerWindow = plugin.getConfig().getDouble(path("max-vanilla-hp-per-window"), 4.0);
    }

    public void start() {
        stop();
        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pruneWindows, 100L, 100L);
    }

    public void stop() {
        if (monitorTask != null) { monitorTask.cancel(); monitorTask = null; }
    }

    @EventHandler
    public void onRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (shouldSkip(player)) return;

        UUID uuid = player.getUniqueId();
        Long exempt = exemptUntil.get(uuid);
        if (exempt != null && System.currentTimeMillis() < exempt) return;

        if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN
            || event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {

            double amount = event.getAmount();
            long now = System.currentTimeMillis();
            Long start = windowStart.get(uuid);
            if (start == null || now - start > windowMs) {
                windowStart.put(uuid, now);
                healedInWindow.put(uuid, amount);
            } else {
                double total = healedInWindow.merge(uuid, amount, Double::sum);
                if (total > maxVanillaHpPerWindow) {
                    flag(player, 1.0, String.format("healed=%.1f in %dms (max=%.1f)",
                        total, now - start, maxVanillaHpPerWindow));
                    windowStart.put(uuid, now);
                    healedInWindow.put(uuid, 0.0);
                }
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        exemptUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 5000);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        healedInWindow.remove(uuid);
        windowStart.remove(uuid);
        exemptUntil.remove(uuid);
    }

    private void pruneWindows() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new java.util.ArrayList<>(windowStart.keySet())) {
            Long start = windowStart.get(uuid);
            if (start != null && now - start > windowMs) {
                windowStart.remove(uuid);
                healedInWindow.remove(uuid);
            }
        }
    }
}
