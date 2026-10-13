package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags stepping up more than the vanilla 0.6-block auto-step without jumping (Step / half-block-cheat hacks). */
public final class StepCheck extends Check implements Listener {

    private final Map<UUID, Integer> suspiciousStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> jumpGraceTicks = new ConcurrentHashMap<>();
    private double minStep;
    private int requiredStreak;

    public StepCheck(AnticheatModule anticheat) {
        super(anticheat, "step", "Step");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minStep = ConfigUtil.getBoundedDouble(plugin, path("min-step"), 0.61, 0.5, 2.0);
        requiredStreak = ConfigUtil.getBoundedInt(plugin, path("required-streak"), 3, 1, 20);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (player.isInsideVehicle() || player.isRiptiding()) return;
        UUID uuid = player.getUniqueId();

        int grace = jumpGraceTicks.merge(uuid, -1, (a, b) -> Math.max(0, a + b));
        if (player.getVelocity().getY() > 0.1) jumpGraceTicks.put(uuid, 10);

        double dy = event.getTo().getY() - event.getFrom().getY();
        boolean onGroundBefore = player.isOnGround();
        double compensation = anticheat.getTpsMonitor().getCompensationFactor();
        double effectiveMinStep = minStep * compensation;
        if (dy > 0 && dy <= 1.0 && dy >= effectiveMinStep && grace <= 0 && onGroundBefore) {
            int streak = suspiciousStreak.merge(uuid, 1, Integer::sum);
            if (streak >= requiredStreak) {
                flag(player, 1.0, String.format("stepStreak=%d dy=%.2f", streak, dy));
                suspiciousStreak.put(uuid, 0);
            }
        } else {
            suspiciousStreak.put(uuid, 0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        suspiciousStreak.remove(uuid);
        jumpGraceTicks.remove(uuid);
    }
}
