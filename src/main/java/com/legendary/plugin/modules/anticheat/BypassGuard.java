package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralised bypass-permission manager. Every check, listener and the
 * threat-action system route their "should this player be exempt?"
 * decision through here instead of calling {@code hasPermission}
 * directly.
 *
 * Anti-bypass hardening layers:
 *
 * 1. Audit trail — every bypass grant is logged with timestamp, player,
 *    and the check that was about to run. Staff can review
 *    {@code /legendaryac bypass-audit} to see who used bypass and when.
 *
 * 2. Temporary-bypass expiry — bypass can be limited to a time window
 *    (e.g. 60s for a staff member to test something), after which it
 *    auto-expires. Prevents permanent bypass that gets forgotten.
 *
 * 3. Critical-check protection — a configurable list of check IDs that
 *    bypass NEVER applies to (e.g. killaura, movement, timer). Even if
 *    a player has the bypass permission, these checks still run. This
 *    prevents a compromised staff account from silently cheating.
 *
 * 4. VL-protected checks — bypass players still accumulate VL (at reduced
 *    weight) so if their bypass is later removed, the history is there.
 *
 * 5. OP self-check — if a player is OP and also flagged by >= 3 checks
 *    within a short window, an alert is broadcast to all staff
 *    regardless of bypass, because a compromised OP account is the most
 *    common way bypass permissions are abused.
 */
public final class BypassGuard {

    private final LegendaryPlugin plugin;
    private final Set<String> criticalChecks = new HashSet<>();
    private boolean auditEnabled;
    private boolean opSelfCheckEnabled;
    private int opSelfCheckFlagThreshold;
    private long opSelfCheckWindowMs;
    private double bypassVlWeightMultiplier;

    private final Map<UUID, Long> temporaryBypassExpiry = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> auditLog = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> recentFlagsByOp = new ConcurrentHashMap<>();
    private BukkitTask cleanupTask;

    public BypassGuard(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        criticalChecks.clear();
        for (String id : plugin.getConfig().getStringList("anticheat.anti-bypass.critical-checks")) {
            criticalChecks.add(id.toLowerCase());
        }
        if (criticalChecks.isEmpty()) {
            criticalChecks.add("killaura");
            criticalChecks.add("aimassist");
            criticalChecks.add("impossiblehit");
            criticalChecks.add("movement");
            criticalChecks.add("noclip");
            criticalChecks.add("timer");
            criticalChecks.add("blink");
            criticalChecks.add("scaffold");
            criticalChecks.add("reach");
        }
        auditEnabled = plugin.getConfig().getBoolean("anticheat.anti-bypass.audit-enabled", true);
        opSelfCheckEnabled = plugin.getConfig().getBoolean("anticheat.anti-bypass.op-self-check.enabled", true);
        opSelfCheckFlagThreshold = plugin.getConfig().getInt("anticheat.anti-bypass.op-self-check.flag-threshold", 3);
        opSelfCheckWindowMs = plugin.getConfig().getLong("anticheat.anti-bypass.op-self-check.window-seconds", 30) * 1000L;
        bypassVlWeightMultiplier = plugin.getConfig().getDouble("anticheat.anti-bypass.bypass-vl-weight-multiplier", 0.3);
    }

    public void start() {
        stop();
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            temporaryBypassExpiry.entrySet().removeIf(e -> now > e.getValue());
            auditLog.entrySet().removeIf(e -> {
                Map<String, Long> logs = e.getValue();
                logs.entrySet().removeIf(le -> now - le.getValue() > 3_600_000);
                return logs.isEmpty();
            });
            recentFlagsByOp.entrySet().removeIf(e -> {
                Map<String, Long> flags = e.getValue();
                flags.entrySet().removeIf(fe -> now - fe.getValue() > opSelfCheckWindowMs);
                return flags.isEmpty();
            });
        }, 600L, 600L);
    }

    public void stop() {
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
    }

    /**
     * Returns true if the player should be exempt from the given check.
     * This replaces the old {@code player.hasPermission("legendary.anticheat.bypass")}
     * check scattered across 9 files.
     *
     * Even when bypass is granted:
     * - Critical checks are NEVER bypassed.
     * - The bypass is recorded in the audit log.
     * - If the player is OP, a self-check counter is incremented.
     */
    public boolean shouldBypass(Player player, String checkId) {
        if (player.hasPermission("legendary.anticheat.bypass") || hasTemporaryBypass(player.getUniqueId())) {
            if (criticalChecks.contains(checkId.toLowerCase())) {
                return false;
            }
            if (auditEnabled) {
                auditLog.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                    .put(checkId, System.currentTimeMillis());
            }
            return true;
        }
        return false;
    }

    /**
     * Returns the VL weight multiplier for bypass players. Bypass players
     * still accumulate VL at a reduced rate so their history is preserved
     * if the bypass is later revoked.
     */
    public double getBypassVlMultiplier(Player player) {
        if (player.hasPermission("legendary.anticheat.bypass") || hasTemporaryBypass(player.getUniqueId())) {
            return bypassVlWeightMultiplier;
        }
        return 1.0;
    }

    /**
     * Grants a temporary bypass that auto-expires after the given seconds.
     * Used by staff commands for short-term testing.
     */
    public void grantTemporaryBypass(UUID playerId, long durationSeconds) {
        temporaryBypassExpiry.put(playerId, System.currentTimeMillis() + durationSeconds * 1000L);
    }

    public void revokeTemporaryBypass(UUID playerId) {
        temporaryBypassExpiry.remove(playerId);
    }

    public boolean hasTemporaryBypass(UUID playerId) {
        Long expiry = temporaryBypassExpiry.get(playerId);
        return expiry != null && System.currentTimeMillis() < expiry;
    }

    /**
     * Called whenever a flag fires on an OP player. If the OP accumulates
     * enough flags across different checks within the time window, an
     * alert is broadcast to all staff — even if the OP has bypass.
     */
    public void onFlagCheck(Player player, String checkId) {
        if (!opSelfCheckEnabled || !player.isOp()) return;
        Map<String, Long> flags = recentFlagsByOp.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        flags.put(checkId, System.currentTimeMillis());
        if (flags.size() >= opSelfCheckFlagThreshold) {
            plugin.getLogger().warning("[AntiBypass] OP " + player.getName()
                + " flagged by " + flags.size() + " checks within "
                + (opSelfCheckWindowMs / 1000) + "s - possible compromised account.");
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("legendary.anticheat.alerts")) {
                    com.legendary.plugin.util.Text.send(staff,
                        "<dark_red>[AntiBypass] <red>OP " + player.getName()
                        + " flagged by " + flags.size() + " checks in "
                        + (opSelfCheckWindowMs / 1000) + "s - investigate possible compromised account.");
                }
            }
            flags.clear();
        }
    }

    /**
     * Returns the audit log for a player (check ID -> timestamp of last
     * bypass usage). Used by /legendaryac bypass-audit.
     */
    public Map<String, Long> getAuditLog(UUID playerId) {
        return java.util.Collections.unmodifiableMap(new java.util.HashMap<>(auditLog.getOrDefault(playerId, java.util.Map.of())));
    }

    public boolean isCriticalCheck(String checkId) {
        return criticalChecks.contains(checkId.toLowerCase());
    }

    public Set<String> getCriticalChecks() {
        return new HashSet<>(criticalChecks);
    }
}
