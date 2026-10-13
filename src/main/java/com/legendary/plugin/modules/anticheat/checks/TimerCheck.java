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

/** Uses a bounded tick-credit balance so network bursts after lag are tolerated. */
public final class TimerCheck extends Check implements Listener {

    private static final double MAX_CREDIT = 20.0;
    private final Map<UUID, TimerState> states = new ConcurrentHashMap<>();
    private int toleratedDebt;

    public TimerCheck(AnticheatModule anticheat) {
        super(anticheat, "timer", "Timer");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        toleratedDebt = ConfigUtil.getBoundedInt(plugin, path("tolerated-tick-debt"), 5, 1, 20);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player) || event.getTo() == null) return;
        if (event.getTo().getX() == event.getFrom().getX()
            && event.getTo().getY() == event.getFrom().getY()
            && event.getTo().getZ() == event.getFrom().getZ()) return;

        long now = System.currentTimeMillis();
        TimerState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new TimerState(now));
        double elapsedTicks = Math.max(0.0, (now - state.lastPacketAt) / 50.0);
        state.credit = Math.min(MAX_CREDIT, state.credit + elapsedTicks) - 1.0;
        state.lastPacketAt = now;

        if (state.credit < 0) {
            state.debtStreak++;
            if (state.debtStreak >= toleratedDebt && state.credit <= -toleratedDebt) {
                flag(player, 1.0, "tick-credit=" + String.format("%.1f", state.credit));
                state.credit = 10.0;
                state.debtStreak = 0;
            }
        } else {
            state.debtStreak = Math.max(0, state.debtStreak - 1);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private static final class TimerState {
        private long lastPacketAt;
        private double credit = MAX_CREDIT;
        private int debtStreak;

        private TimerState(long now) {
            lastPacketAt = now;
        }
    }
}
