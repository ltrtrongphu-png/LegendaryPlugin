package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks how many times a player has been flagged per check and decides
 * when to warn / kick / ban based on config-defined thresholds
 * ("alert,alert,kick,ban" style escalation strings). Ported from
 * BaB.EscalationManager.
 */
public final class EscalationManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Map<String, Integer>> levels = new ConcurrentHashMap<>();

    public EscalationManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        // Reserved for future global escalation tuning; per-check actions currently
        // come from the "actionsConfig" string passed into escalate().
    }

    /** actionsConfig e.g. "alert,alert,kick,ban" - one entry consumed per call, last entry repeats. */
    public void escalate(Player player, String checkId, String displayName, String actionsConfig) {
        Map<String, Integer> map = levels.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        int step = map.merge(checkId, 1, Integer::sum) - 1;
        String[] actions = actionsConfig.split(",");
        String action = actions[Math.min(step, actions.length - 1)].trim();
        applyAction(player, checkId, displayName, action);
    }

    private void applyAction(Player player, String checkId, String displayName, String action) {
        switch (action.toLowerCase()) {
            case "alert" -> { /* AlertManager already notified staff on flag() */ }
            case "kick" -> plugin.getServer().getScheduler().runTask(plugin, () ->
                player.kick(com.legendary.plugin.util.Text.of("<red>Kicked by anti-cheat: " + displayName)));
            case "ban" -> {
                double vl = plugin.getAnticheatModule().getViolationManager().getVl(player, checkId);
                plugin.getAnticheatModule().getBanExecutor().ban(player, displayName, vl);
                plugin.getAnticheatModule().getDatabaseManager().recordBanAsync(
                    player.getUniqueId().toString(), player.getName(), checkId, vl,
                    "AutoBan: " + displayName + " (VL " + String.format("%.1f", vl) + ")");
            }
            default -> plugin.getLogger().warning("Unknown escalation action: " + action);
        }
    }

    public void resetPlayer(UUID uuid) {
        levels.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        resetPlayer(event.getPlayer().getUniqueId());
    }
}
