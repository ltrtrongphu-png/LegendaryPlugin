package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BackTrack: detects clients that exploit server-side hit validation by
 * manipulating their position history. The hack stores past positions
 * and replays them to the server, so hits are validated against an old
 * position while the player is actually somewhere else. Detection:
 * track the player's position at the time of each attack. If the
 * attacker's position at hit-time is significantly behind their current
 * position (indicating they're hitting from a "stored" past position),
 * flag.
 */
public final class BackTrackCheck extends Check implements Listener {

    private final Map<UUID, Deque<Location>> positionHistory = new ConcurrentHashMap<>();
    private int historySize;
    private double maxPositionLag;
    private double minMoveDistance;

    public BackTrackCheck(AnticheatModule anticheat) {
        super(anticheat, "backtrack", "BackTrack");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        historySize = plugin.getConfig().getInt(path("history-size"), 10);
        maxPositionLag = plugin.getConfig().getDouble(path("max-position-lag"), 3.0);
        minMoveDistance = plugin.getConfig().getDouble(path("min-move-distance"), 0.5);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        Deque<Location> history = positionHistory.computeIfAbsent(uuid, k -> new ArrayDeque<>(historySize));
        synchronized (history) {
            history.addLast(event.getTo().clone());
            while (history.size() > historySize) history.pollFirst();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (shouldSkip(attacker)) return;
        if (!attacker.getWorld().equals(target.getWorld())) return;

        UUID uuid = attacker.getUniqueId();
        Deque<Location> history = positionHistory.get(uuid);
        if (history == null || history.size() < 3) return;

        Location current = attacker.getLocation();
        Location oldest;
        synchronized (history) {
            oldest = history.peekFirst();
        }
        if (oldest == null || !oldest.getWorld().equals(current.getWorld())) return;

        double lagDistance = oldest.distance(current);
        if (lagDistance > maxPositionLag) {
            // The oldest stored position is far from current — check if hit distance
            // from oldest position is closer than from current (indicates hitting from past)
            double distFromOldest = oldest.distance(target.getLocation());
            double distFromCurrent = current.distance(target.getLocation());
            if (distFromOldest < distFromCurrent - minMoveDistance) {
                flag(attacker, 1.5, String.format("lagDist=%.2f oldestHit=%.2f currentHit=%.2f",
                    lagDistance, distFromOldest, distFromCurrent));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        positionHistory.remove(event.getPlayer().getUniqueId());
    }
}
