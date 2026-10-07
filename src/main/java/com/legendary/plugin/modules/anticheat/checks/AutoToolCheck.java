package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NEW CHECK (2026-09-19 DeepSeek-dump merge review). Flags a hotbar-slot
 * switch to a tool immediately (within min-interval-ms) before breaking a
 * block, repeated back to back - the "AutoTool" hack signature where the
 * client silently selects the fastest-mining tool for whatever block is
 * targeted, faster than a human deliberately pressing a number key.
 */
public final class AutoToolCheck extends Check implements Listener {

    private final Map<UUID, Long> lastSwitchTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> rapidStreak = new ConcurrentHashMap<>();
    private long minIntervalMs;
    private int requiredStreak;

    public AutoToolCheck(AnticheatModule anticheat) {
        super(anticheat, "autotool", "AutoTool");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minIntervalMs = ConfigUtil.getBoundedLong(plugin, path("min-interval-ms"), 100, 20, 1000);
        requiredStreak = ConfigUtil.getBoundedInt(plugin, path("required-streak"), 3, 1, 10);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        lastSwitchTime.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        Long lastSwitch = lastSwitchTime.get(uuid);
        if (lastSwitch == null) {
            rapidStreak.put(uuid, 0);
            return;
        }
        long elapsed = System.currentTimeMillis() - lastSwitch;
        if (elapsed < minIntervalMs) {
            int streak = rapidStreak.merge(uuid, 1, Integer::sum);
            if (streak >= requiredStreak) {
                flag(player, 1.0, "switchToBreakGap=" + elapsed + "ms streak=" + streak);
                rapidStreak.put(uuid, 0);
            }
        } else {
            rapidStreak.merge(uuid, -1, (a, b) -> Math.max(0, a + b));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastSwitchTime.remove(uuid);
        rapidStreak.remove(uuid);
    }
}
