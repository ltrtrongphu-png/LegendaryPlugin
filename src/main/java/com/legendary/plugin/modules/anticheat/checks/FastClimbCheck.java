package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import com.legendary.plugin.modules.anticheat.physics.VanillaPhysics;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NEW CHECK (2026-09-20, added after reviewing a well-known open-source
 * hack client's module taxonomy to find detection gaps - see
 * movement/FastClimb in that project). Vanilla climbing speed on a
 * ladder/vine/scaffolding is capped low (~0.117 blocks/tick); a
 * "fast climb" hack raises that cap so players scale builds unnaturally
 * fast. Flags sustained vertical climbing speed above the vanilla cap.
 */
public final class FastClimbCheck extends Check implements Listener {

    private final Map<UUID, Integer> streak = new ConcurrentHashMap<>();
    private double maxClimbSpeed;
    private int requiredStreak;

    public FastClimbCheck(AnticheatModule anticheat) {
        super(anticheat, "fastclimb", "FastClimb");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxClimbSpeed = plugin.getConfig().getDouble(path("max-climb-speed"), 0.15);
        requiredStreak = ConfigUtil.getBoundedInt(plugin, path("required-streak"), 4, 1, 20);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player) || !player.isClimbing()) return;

        double dy = event.getTo().getY() - event.getFrom().getY();
        if (dy <= 0) { // only the climbing-UP direction is capped by vanilla; descending is normal
            streak.put(player.getUniqueId(), 0);
            return;
        }
        double compensation = anticheat.getTpsMonitor().getCompensationFactor();
        double cap = maxClimbSpeed * VanillaPhysics.horizontalSpeedMultiplier(player) * compensation;
        if (dy > cap) {
            int count = streak.merge(player.getUniqueId(), 1, Integer::sum);
            if (count >= requiredStreak) {
                flag(player, 1.0, String.format("climbSpeed=%.3f cap=%.3f", dy, cap));
                streak.put(player.getUniqueId(), 0);
            }
        } else {
            streak.merge(player.getUniqueId(), -1, (a, b) -> Math.max(0, a + b));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        streak.remove(event.getPlayer().getUniqueId());
    }
}
