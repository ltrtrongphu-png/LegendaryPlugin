package com.legendary.plugin.modules.security;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Application-layer DDoS protection for self-hosted Minecraft servers
 * without a proxy/CDN front. Enforces global and per-IP connection rate
 * limits, auto-blacklists IPs that flood, and provides an emergency
 * lockdown mode that whitelists known players during active attacks.
 */
public final class AntiDdosManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<String, Long> blacklistedIps = new ConcurrentHashMap<>();
    private final Map<String, Long> ipBlacklistExpiry = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> concurrentConnections = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> recentLogins = new ConcurrentHashMap<>();
    private final Deque<Long> globalLoginTimestamps = new ArrayDeque<>();
    private final Set<String> whitelistedIps = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> ipViolationCount = new ConcurrentHashMap<>();

    private volatile boolean lockdownMode = false;
    private volatile long lastAttackLogTime = 0L;

    private boolean enabled;
    private int maxGlobalLoginsPerSecond;
    private int maxPerIpPerMinute;
    private int maxConcurrentPerIp;
    private int violationsBeforeBlacklist;
    private long blacklistDurationMs;
    private boolean autoBlacklistEnabled;
    private BukkitTask cleanupTask;

    public AntiDdosManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("anti-ddos.enabled", true);
        maxGlobalLoginsPerSecond = plugin.getConfig().getInt("anti-ddos.max-global-logins-per-second", 10);
        maxPerIpPerMinute = plugin.getConfig().getInt("anti-ddos.max-per-ip-per-minute", 5);
        maxConcurrentPerIp = plugin.getConfig().getInt("anti-ddos.max-concurrent-per-ip", 3);
        violationsBeforeBlacklist = plugin.getConfig().getInt("anti-ddos.violations-before-blacklist", 3);
        blacklistDurationMs = plugin.getConfig().getLong("anti-ddos.blacklist-duration-ms", 900_000L);
        autoBlacklistEnabled = plugin.getConfig().getBoolean("anti-ddos.auto-blacklist.enabled", true);
        for (String ip : plugin.getConfig().getStringList("anti-ddos.whitelist-ips")) {
            whitelistedIps.add(ip.trim());
        }
    }

    public void start() {
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin, this::cleanupStale, 200L, 200L);
        plugin.getLogger().info("Anti-DDoS: active (max "
            + maxGlobalLoginsPerSecond + "/s global, "
            + maxPerIpPerMinute + "/min per-IP, "
            + maxConcurrentPerIp + " concurrent per-IP)");
    }

    public void stop() {
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        blacklistedIps.clear();
        ipBlacklistExpiry.clear();
        concurrentConnections.clear();
        recentLogins.clear();
        synchronized (globalLoginTimestamps) { globalLoginTimestamps.clear(); }
        ipViolationCount.clear();
        HandlerList.unregisterAll(this);
    }

    public boolean isLockdownMode() { return lockdownMode; }
    public int getBlacklistSize() { return blacklistedIps.size(); }

    public void setLockdown(boolean enabled) {
        lockdownMode = enabled;
        if (enabled) {
            plugin.getLogger().severe("[Anti-DDoS] EMERGENCY LOCKDOWN ACTIVATED - only whitelisted players can join.");
            notifyStaff("<red>[Anti-DDoS] <dark_red>EMERGENCY LOCKDOWN ACTIVATED<gray>. Only whitelisted players may join.");
        } else {
            plugin.getLogger().info("[Anti-DDoS] Emergency lockdown deactivated.");
            notifyStaff("<green>[Anti-DDoS] Lockdown deactivated. Normal operation resumed.");
        }
    }

    public void blacklistIp(String ip, long durationMs) {
        blacklistedIps.put(ip, System.currentTimeMillis());
        ipBlacklistExpiry.put(ip, System.currentTimeMillis() + durationMs);
        plugin.getLogger().warning("[Anti-DDoS] Blacklisted IP: " + ip + " for " + (durationMs / 1000) + "s");
        notifyStaff("<red>[Anti-DDoS] Blacklisted <white>" + ip + " <red>for " + (durationMs / 1000) + "s");
    }

    public void unblacklistIp(String ip) {
        blacklistedIps.remove(ip);
        ipBlacklistExpiry.remove(ip);
        ipViolationCount.remove(ip);
    }

    public boolean isBlacklisted(String ip) {
        Long expiry = ipBlacklistExpiry.get(ip);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            blacklistedIps.remove(ip);
            ipBlacklistExpiry.remove(ip);
            return false;
        }
        return true;
    }

    public Map<String, Long> getBlacklistedIps() {
        Map<String, Long> result = new HashMap<>();
        long now = System.currentTimeMillis();
        for (var entry : ipBlacklistExpiry.entrySet()) {
            if (now < entry.getValue()) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public Set<String> getWhitelistedIps() {
        return whitelistedIps;
    }

    public void addWhitelist(String ip) {
        whitelistedIps.add(ip.trim());
    }

    public void removeWhitelist(String ip) {
        whitelistedIps.remove(ip.trim());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        if (!enabled) return;
        InetAddress address = event.getAddress();
        String ip = address == null ? "unknown" : address.getHostAddress();

        if (isBlacklisted(ip)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                com.legendary.plugin.util.Text.of("<red>Your IP is temporarily blocked due to suspicious activity."));
            logAttack("Blocked blacklisted IP: " + ip);
            return;
        }

        if (lockdownMode && !whitelistedIps.contains(ip)
                && !event.getPlayer().hasPermission("legendary.bypass.ddos")) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                com.legendary.plugin.util.Text.of("<red>Server is in emergency lockdown. Please try again later."));
            logAttack("Blocked by lockdown: " + ip);
            return;
        }

        long now = System.currentTimeMillis();

        if (isOverGlobalLimit(now)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                com.legendary.plugin.util.Text.of("<red>Server is experiencing high traffic. Please try again later."));
            recordViolation(ip);
            logAttack("Global login rate exceeded: " + ip);
            return;
        }

        if (isOverIpLimit(ip, now)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                com.legendary.plugin.util.Text.of("<red>Too many connections from your network. Please try again later."));
            recordViolation(ip);
            logAttack("Per-IP login rate exceeded: " + ip);
            return;
        }

        int concurrent = concurrentConnections.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        if (concurrent > maxConcurrentPerIp) {
            concurrentConnections.get(ip).decrementAndGet();
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                com.legendary.plugin.util.Text.of("<red>Too many concurrent connections from your network."));
            recordViolation(ip);
            logAttack("Concurrent connection limit exceeded: " + ip + " (" + concurrent + ")");
            return;
        }

        synchronized (globalLoginTimestamps) {
            globalLoginTimestamps.addLast(now);
            while (!globalLoginTimestamps.isEmpty() && now - globalLoginTimestamps.peekFirst() > 1000L) {
                globalLoginTimestamps.pollFirst();
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        InetAddress address = player.getAddress() == null ? null : player.getAddress().getAddress();
        String ip = address == null ? "unknown" : address.getHostAddress();
        AtomicInteger count = concurrentConnections.get(ip);
        if (count != null) {
            count.decrementAndGet();
        }
    }

    private boolean isOverGlobalLimit(long now) {
        synchronized (globalLoginTimestamps) {
            while (!globalLoginTimestamps.isEmpty() && now - globalLoginTimestamps.peekFirst() > 1000L) {
                globalLoginTimestamps.pollFirst();
            }
            return globalLoginTimestamps.size() >= maxGlobalLoginsPerSecond;
        }
    }

    private boolean isOverIpLimit(String ip, long now) {
        Deque<Long> deque = recentLogins.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && now - deque.peekFirst() > 60_000L) {
                deque.pollFirst();
            }
            return deque.size() > maxPerIpPerMinute;
        }
    }

    private void recordViolation(String ip) {
        int count = ipViolationCount.merge(ip, 1, Integer::sum);
        if (autoBlacklistEnabled && count >= violationsBeforeBlacklist) {
            blacklistIp(ip, blacklistDurationMs);
            ipViolationCount.put(ip, 0);
        }
    }

    private void logAttack(String detail) {
        long now = System.currentTimeMillis();
        if (now - lastAttackLogTime < 2000L) return;
        lastAttackLogTime = now;
        plugin.getLogger().warning("[Anti-DDoS] " + detail);
    }

    private void cleanupStale() {
        long now = System.currentTimeMillis();
        ipBlacklistExpiry.entrySet().removeIf(e -> now > e.getValue());
        for (var entry : recentLogins.entrySet()) {
            synchronized (entry.getValue()) {
                entry.getValue().removeIf(t -> now - t > 120_000L);
            }
        }
        concurrentConnections.entrySet().removeIf(e -> e.getValue().get() <= 0);
    }

    private void notifyStaff(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("legendary.security.alerts")) {
                com.legendary.plugin.util.Text.send(p, message);
            }
        }
    }
}
