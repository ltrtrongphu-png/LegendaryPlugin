package com.legendary.plugin.modules.security;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.UUID;
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
    private final Map<String, AtomicInteger> inFlightConnections = new ConcurrentHashMap<>();
    private final Map<UUID, LoginReservation> loginReservations = new ConcurrentHashMap<>();
    private final Set<AsyncPlayerPreLoginEvent> reservedPreLoginEvents = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> joinedPlayerIps = new ConcurrentHashMap<>();
    private final Map<String, Long> successfulIps = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> recentLogins = new ConcurrentHashMap<>();
    private final Map<String, Long> ipViolationLastSeen = new ConcurrentHashMap<>();
    private final Deque<Long> globalLoginTimestamps = new ArrayDeque<>();
    private final Set<String> whitelistedIps = ConcurrentHashMap.newKeySet();
    private final Set<UUID> whitelistedUuids = ConcurrentHashMap.newKeySet();
    private volatile List<Cidr> trustedProxies = List.of();
    private volatile List<Cidr> whitelistCidrs = List.of();
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
        whitelistedIps.clear();
        plugin.getConfig().getStringList("anti-ddos.whitelist-ips").stream()
            .map(String::trim).filter(value -> !value.isEmpty()).forEach(whitelistedIps::add);
        whitelistedUuids.clear();
        for (String uuid : plugin.getConfig().getStringList("anti-ddos.whitelist-uuids")) {
            try { whitelistedUuids.add(UUID.fromString(uuid)); }
            catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid anti-ddos.whitelist-uuids entry: " + uuid);
            }
        }
        trustedProxies = parseCidrs(plugin.getConfig().getStringList("anti-ddos.trusted-proxies"));
        whitelistCidrs = parseCidrs(plugin.getConfig().getStringList("anti-ddos.whitelist-ips"));
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
        inFlightConnections.clear();
        loginReservations.clear();
        reservedPreLoginEvents.clear();
        joinedPlayerIps.clear();
        successfulIps.clear();
        recentLogins.clear();
        synchronized (globalLoginTimestamps) { globalLoginTimestamps.clear(); }
        ipViolationCount.clear();
        ipViolationLastSeen.clear();
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
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!enabled) return;
        InetAddress address = event.getAddress();
        String ip = address == null ? "unknown" : normalizeAddress(address);
        boolean trustedProxy = address != null && matchesAny(address, trustedProxies);
        boolean whitelisted = whitelistedUuids.contains(event.getUniqueId())
            || whitelistedIps.contains(ip) || address != null && matchesAny(address, whitelistCidrs);
        if (trustedProxy) return;
        if (lockdownMode && !whitelisted) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "Server is in emergency lockdown. Please try again later.");
            logAttack("Blocked by lockdown: " + ip);
            return;
        }
        if (whitelisted) return;
        if (isBlacklisted(ip)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "Your IP is temporarily blocked due to suspicious activity.");
            logAttack("Blocked blacklisted IP: " + ip);
            return;
        }
        if (isOverIpLimit(ip, System.currentTimeMillis())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "Too many connections from your network. Please try again later.");
            recordViolationUnlessKnown(ip);
            logAttack("Per-IP login rate exceeded: " + ip);
            return;
        }
        long now = System.currentTimeMillis();
        if (isOverGlobalLimit(now)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "Server is experiencing high traffic. Please try again later.");
            logAttack("Global login rate exceeded");
            return;
        }
        int active = concurrentConnections.getOrDefault(ip, new AtomicInteger()).get();
        AtomicInteger inFlight = inFlightConnections.computeIfAbsent(ip, ignored -> new AtomicInteger());
        int pending = inFlight.incrementAndGet();
        if (active + pending > maxConcurrentPerIp) {
            inFlight.decrementAndGet();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "Too many concurrent connections from your network.");
            recordViolationUnlessKnown(ip);
            logAttack("Concurrent connection limit exceeded: " + ip);
            return;
        }
        LoginReservation reservation = new LoginReservation(ip, now + 60_000L);
        if (loginReservations.putIfAbsent(event.getUniqueId(), reservation) != null) {
            decrement(inFlightConnections, ip);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "A connection for this account is already being processed.");
            return;
        }
        reservedPreLoginEvents.add(event);
        synchronized (globalLoginTimestamps) {
            globalLoginTimestamps.addLast(now);
            while (!globalLoginTimestamps.isEmpty() && now - globalLoginTimestamps.peekFirst() > 1000L) {
                globalLoginTimestamps.pollFirst();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginFinal(AsyncPlayerPreLoginEvent event) {
        if (reservedPreLoginEvents.remove(event)
            && event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            releaseReservation(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) releaseReservation(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        LoginReservation reservation = loginReservations.remove(uuid);
        InetAddress address = event.getPlayer().getAddress() == null ? null : event.getPlayer().getAddress().getAddress();
        String ip = reservation != null ? reservation.ip()
            : address == null ? "unknown" : normalizeAddress(address);
        if (reservation != null) decrement(inFlightConnections, reservation.ip());
        joinedPlayerIps.put(uuid, ip);
        if (!"unknown".equals(ip)) {
            concurrentConnections.computeIfAbsent(ip, ignored -> new AtomicInteger()).incrementAndGet();
            successfulIps.put(ip, System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String ip = joinedPlayerIps.remove(event.getPlayer().getUniqueId());
        if (ip != null) decrement(concurrentConnections, ip);
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

    private void recordViolationUnlessKnown(String ip) {
        if (successfulIps.containsKey(ip)) return;
        if (trustedProxies.stream().anyMatch(cidr -> cidr.matchesText(ip))) return;
        int count = ipViolationCount.merge(ip, 1, Integer::sum);
        ipViolationLastSeen.put(ip, System.currentTimeMillis());
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
        successfulIps.entrySet().removeIf(e -> now - e.getValue() > 86_400_000L);
        loginReservations.entrySet().removeIf(entry -> {
            if (now <= entry.getValue().expiresAt()) return false;
            decrement(inFlightConnections, entry.getValue().ip());
            return true;
        });
        for (var entry : recentLogins.entrySet()) {
            boolean empty;
            synchronized (entry.getValue()) {
                entry.getValue().removeIf(t -> now - t > 120_000L);
                empty = entry.getValue().isEmpty();
            }
            if (empty) recentLogins.remove(entry.getKey(), entry.getValue());
        }
        ipViolationLastSeen.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= 600_000L) return false;
            ipViolationCount.remove(entry.getKey());
            return true;
        });
        concurrentConnections.entrySet().removeIf(e -> e.getValue().get() <= 0);
        inFlightConnections.entrySet().removeIf(e -> e.getValue().get() <= 0);
    }

    private void notifyStaff(String message) {
        Runnable notify = () -> Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.hasPermission("legendary.security.alerts"))
            .forEach(p -> com.legendary.plugin.util.Text.send(p, message));
        if (Bukkit.isPrimaryThread()) notify.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, notify);
    }

    private void releaseReservation(UUID uuid) {
        LoginReservation reservation = loginReservations.remove(uuid);
        if (reservation != null) decrement(inFlightConnections, reservation.ip());
    }

    private static void decrement(Map<String, AtomicInteger> counters, String key) {
        AtomicInteger value = counters.get(key);
        if (value == null) return;
        int remaining = value.updateAndGet(count -> Math.max(0, count - 1));
        if (remaining == 0) counters.remove(key, value);
    }

    static List<Cidr> parseCidrs(List<String> values) {
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).map(value -> {
            try { return Cidr.parse(value); }
            catch (Exception exception) {
                throw new IllegalArgumentException("Invalid anti-ddos CIDR: " + value, exception);
            }
        }).toList();
    }

    private static boolean matchesAny(InetAddress address, List<Cidr> cidrs) {
        return cidrs.stream().anyMatch(cidr -> cidr.matches(address));
    }

    static String normalizeAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            return "v6/64:" + HexFormat.of().formatHex(bytes, 0, 8);
        }
        return address.getHostAddress();
    }

    private record LoginReservation(String ip, long expiresAt) {}

    record Cidr(byte[] address, int prefix) {
        static Cidr parse(String raw) throws Exception {
            String[] parts = raw.split("/", 2);
            byte[] address = InetAddress.getByName(parts[0]).getAddress();
            int maxPrefix = address.length * 8;
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : maxPrefix;
            if (prefix < 0 || prefix > maxPrefix) throw new IllegalArgumentException("CIDR prefix out of range");
            return new Cidr(address, prefix);
        }

        boolean matches(InetAddress candidate) {
            byte[] candidateBytes = candidate.getAddress();
            if (candidateBytes.length != address.length) return false;
            int completeBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < completeBytes; i++) if (candidateBytes[i] != address[i]) return false;
            if (remainingBits == 0) return true;
            int mask = 0xff << (8 - remainingBits);
            return (candidateBytes[completeBytes] & mask) == (address[completeBytes] & mask);
        }

        boolean matchesText(String ip) {
            try { return matches(InetAddress.getByName(ip)); }
            catch (Exception ignored) { return false; }
        }
    }
}
