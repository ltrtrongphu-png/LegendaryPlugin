package com.legendary.plugin.modules.esp;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.ConfigUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects "free-cam" ESP exploits where a hacked client detaches the
 * camera (commonly by falling far below the world / into the void while
 * still "controlling" the visible player body) to scout terrain. Ported
 * from BaB.AntiFreeCamManager.
 */
public final class AntiFreeCamManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Integer> belowThresholdTicks = new HashMap<>();
    private int yThreshold;
    private int durationTicks;
    private BukkitTask task;

    public AntiFreeCamManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        yThreshold = ConfigUtil.getBoundedInt(plugin, "esp.anti-freecam.y-threshold", -64, -2048, 320);
        durationTicks = ConfigUtil.getBoundedInt(plugin, "esp.anti-freecam.duration-ticks", 100, 20, 6000);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("esp.anti-freecam.enabled", true)) return;
        stop();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
        belowThresholdTicks.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        belowThresholdTicks.remove(event.getPlayer().getUniqueId());
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("legendary.esp.bypass") || player.getGameMode() == GameMode.SPECTATOR) {
                belowThresholdTicks.remove(player.getUniqueId());
                continue;
            }
            boolean below = player.getLocation().getY() < yThreshold;
            int ticks = belowThresholdTicks.merge(player.getUniqueId(), below ? 20 : -20,
                (oldVal, delta) -> Math.max(0, oldVal + delta));
            if (ticks >= durationTicks) {
                String detail = "belowY=" + yThreshold + " forSeconds=" + (ticks / 20);
                // Route through the anti-cheat pipeline (violations/alerts/escalation/DB history)
                // instead of only logging to console, so free-cam detections are treated with the
                // same seriousness as every other check and show up in /legendaryac vl and history.
                plugin.getModule("anticheat")
                    .filter(m -> m instanceof com.legendary.plugin.modules.anticheat.AnticheatModule)
                    .map(m -> (com.legendary.plugin.modules.anticheat.AnticheatModule) m)
                    .ifPresentOrElse(
                        anticheat -> anticheat.flagRaw(player, "freecam", 2.0, detail),
                        () -> plugin.getLogger().warning("[AntiFreeCam] " + player.getName() + " " + detail
                            + " - possible free-cam ESP (anticheat module disabled, alert not escalated).")
                    );
                belowThresholdTicks.put(player.getUniqueId(), 0);
            }
        }
    }
}
