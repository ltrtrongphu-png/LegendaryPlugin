package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EscalationManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Map<String, Integer>> levels = new ConcurrentHashMap<>();
    private final Set<UUID> bannedPlayers = ConcurrentHashMap.newKeySet();

    public EscalationManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
    }

    public void escalate(Player player, String checkId, String displayName, String actionsConfig) {
        UUID uuid = player.getUniqueId();
        if (bannedPlayers.contains(uuid)) return;
        Map<String, Integer> map = levels.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        int step = map.merge(checkId, 1, Integer::sum) - 1;
        String[] actions = actionsConfig.split(",");
        String action = actions[Math.min(step, actions.length - 1)].trim();
        applyAction(player, checkId, displayName, action);
    }

    private void applyAction(Player player, String checkId, String displayName, String action) {
        switch (action.toLowerCase()) {
            case "alert" -> plugin.getMetrics().counter("anticheat.escalation.alerts").increment();
            case "kick" -> {
                plugin.getMetrics().counter("anticheat.escalation.kicks").increment();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.kick(com.legendary.plugin.util.Text.of("<red>Kicked by anti-cheat: " + displayName));
                    }
                });
            }
            case "ban" -> {
                plugin.getMetrics().counter("anticheat.escalation.bans").increment();
                if (!player.isOnline()) return;
                bannedPlayers.add(player.getUniqueId());
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
        bannedPlayers.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        resetPlayer(event.getPlayer().getUniqueId());
    }
}
