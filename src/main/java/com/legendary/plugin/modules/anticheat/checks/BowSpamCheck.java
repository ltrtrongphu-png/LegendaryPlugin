package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NEW CHECK (2026-09-20, gap found by reviewing a hack client's module
 * list: combat/BowSpam, combat/BowAimbot). Every other combat check in
 * this plugin (KillAura, AimAssist, ImpossibleHit, Criticals) only looks
 * at melee hits - ranged combat was completely unmonitored. A fully
 * drawn vanilla bow needs roughly 1 second to charge to full power, so
 * repeated full-power shots far faster than that (a macro/auto-fire
 * hack) are the clearest, lowest-false-positive signal to check for
 * without needing full projectile-trajectory analysis.
 */
public final class BowSpamCheck extends Check implements Listener {

    private final Map<UUID, Long> lastShotAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> streak = new ConcurrentHashMap<>();
    private long minIntervalMs;
    private int requiredStreak;

    public BowSpamCheck(AnticheatModule anticheat) {
        super(anticheat, "bowspam", "BowSpam");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minIntervalMs = ConfigUtil.getBoundedLong(plugin, path("min-interval-ms"), 500, 100, 3000);
        requiredStreak = ConfigUtil.getBoundedInt(plugin, path("required-streak"), 3, 1, 10);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile instanceof Arrow)) return;
        if (!(projectile.getShooter() instanceof Player player)) return;
        if (shouldSkip(player)) return;

        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long last = lastShotAt.put(uuid, now);
        if (last != null && now - last < minIntervalMs) {
            int count = streak.merge(uuid, 1, Integer::sum);
            if (count >= requiredStreak) {
                flag(player, 1.0, "shotInterval=" + (now - last) + "ms streak=" + count);
                streak.put(uuid, 0);
            }
        } else {
            streak.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastShotAt.remove(uuid);
        streak.remove(uuid);
    }
}
