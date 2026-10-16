package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.GameMode;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks transient player states that make movement/combat checks unreliable. */
public final class PlayerStateTracker implements Listener {

    private static final long GRACE_MILLIS = 2_000L;
    private static final long PING_SAMPLE_INTERVAL_MILLIS = 250L;
    private static final long PING_SPIKE_MILLIS = 150L;
    private static final int MAX_PING_SAMPLES = 12;

    private final LegendaryPlugin plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public PlayerStateTracker(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public PlayerContext context(Player player) {
        State state = states.computeIfAbsent(player.getUniqueId(), ignored -> new State());
        long now = System.currentTimeMillis();
        int ping = Math.max(0, player.getPing());
        boolean pingSpike = state.recordPing(ping, now);
        long ticksSinceReset = Math.max(0L, (now - state.lastResetAt) / 50L);
        double tps = plugin.getAnticheatModule() == null
            ? 20.0 : plugin.getAnticheatModule().getTpsMonitor().getTps();
        double mspt = org.bukkit.Bukkit.getAverageTickTime();
        boolean onClimbable = Tag.CLIMBABLE.isTagged(player.getLocation().getBlock().getType());

        return new PlayerContext(player.getGameMode(), player.getAllowFlight(), player.isFlying(),
            player.isInsideVehicle(), player.isGliding(), player.isRiptiding(), player.isSwimming(),
            onClimbable, ticksSinceReset, ping, state.pingVariance(), tps, mspt, pingSpike);
    }

    public boolean shouldSkip(Player player) {
        PlayerContext context = context(player);
        return player.isDead()
            || context.gameMode() == GameMode.CREATIVE
            || context.gameMode() == GameMode.SPECTATOR
            || context.allowFlight()
            || context.flying()
            || context.insideVehicle()
            || context.gliding()
            || context.riptiding()
            || context.ticksSinceReset() < 40
            || context.pingSpike()
            || context.tps() < 15.0
            || context.mspt() > 66.7;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        markReset(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        markReset(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        markReset(event.getPlayer());
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        markReset(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        markReset(event.getPlayer());
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent event) {
        markReset(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private void markReset(Player player) {
        states.computeIfAbsent(player.getUniqueId(), ignored -> new State()).reset();
    }

    public record PlayerContext(GameMode gameMode, boolean allowFlight, boolean flying,
                                boolean insideVehicle, boolean gliding, boolean riptiding,
                                boolean swimming, boolean climbing, long ticksSinceReset,
                                int ping, double pingVariance, double tps, double mspt,
                                boolean pingSpike) {}

    private static final class State {
        private final Deque<Integer> pings = new ArrayDeque<>();
        private volatile long lastResetAt = System.currentTimeMillis();
        private long lastPingSampleAt;

        synchronized boolean recordPing(int ping, long now) {
            if (now - lastPingSampleAt >= PING_SAMPLE_INTERVAL_MILLIS) {
                pings.addLast(ping);
                lastPingSampleAt = now;
                while (pings.size() > MAX_PING_SAMPLES) pings.removeFirst();
            }
            return pingVariance() > PING_SPIKE_MILLIS;
        }

        synchronized double pingVariance() {
            if (pings.size() < 3) return 0;
            double mean = pings.stream().mapToInt(Integer::intValue).average().orElse(0);
            return Math.sqrt(pings.stream().mapToDouble(ping -> (ping - mean) * (ping - mean))
                .average().orElse(0));
        }

        void reset() {
            lastResetAt = System.currentTimeMillis();
            synchronized (this) {
                pings.clear();
                lastPingSampleAt = 0;
            }
        }
    }
}
