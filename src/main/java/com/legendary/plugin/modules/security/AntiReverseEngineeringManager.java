package com.legendary.plugin.modules.security;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Anti-reverse-engineering manager. Detects debuggers/profilers attached to
 * the JVM, class bytecode tampering at runtime, and external plugins that
 * attempt to reflect into the plugin's internal packages. When tampering is
 * detected, the plugin enters lockdown mode: anti-cheat checks are disabled
 * so an attacker can't use a modified plugin to test bypasses, while
 * anti-DDoS protection stays active.
 */
public final class AntiReverseEngineeringManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Set<String> guardedPackages = Set.of(
        "com.legendary.plugin.modules.anticheat",
        "com.legendary.plugin.modules.anticheat.checks",
        "com.legendary.plugin.modules.anticheat.listeners",
        "com.legendary.plugin.modules.security"
    );

    private final Set<String> knownPluginNames = ConcurrentHashMap.newKeySet();
    private final Map<String, String> classHashes = new ConcurrentHashMap<>();
    private volatile boolean lockdownMode = false;
    private volatile boolean debuggerDetected = false;
    private volatile boolean tamperingDetected = false;

    private boolean debuggerCheckEnabled;
    private boolean tamperingCheckEnabled;
    private boolean pluginScanEnabled;
    private boolean lockdownOnTampering;
    private long tamperingCheckIntervalTicks;
    private BukkitTask tamperingTask;

    private static final List<String> SUSPICIOUS_JVM_FLAGS = List.of(
        "-agentlib", "-Xrunhprof", "-javaagent", "-Xdebug",
        "-agentpath", "-Xrunjdwp"
    );

    private static final List<String> GUARDED_CLASSES = List.of(
        "com.legendary.plugin.modules.anticheat.checks.KillAuraCheck",
        "com.legendary.plugin.modules.anticheat.checks.MovementCheck",
        "com.legendary.plugin.modules.anticheat.checks.CriticalsCheck",
        "com.legendary.plugin.modules.anticheat.checks.NoFallCheck",
        "com.legendary.plugin.modules.anticheat.checks.NoSwingCheck",
        "com.legendary.plugin.modules.anticheat.AnticheatModule",
        "com.legendary.plugin.modules.anticheat.ViolationManager",
        "com.legendary.plugin.modules.anticheat.BypassGuard",
        "com.legendary.plugin.modules.security.AntiReverseEngineeringManager",
        "com.legendary.plugin.modules.security.AntiDdosManager"
    );

    public AntiReverseEngineeringManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        debuggerCheckEnabled = plugin.getConfig().getBoolean("anti-reverse-engineering.debugger-detection.enabled", true);
        tamperingCheckEnabled = plugin.getConfig().getBoolean("anti-reverse-engineering.class-tampering-check.enabled", true);
        pluginScanEnabled = plugin.getConfig().getBoolean("anti-reverse-engineering.plugin-scan.enabled", true);
        lockdownOnTampering = plugin.getConfig().getBoolean("anti-reverse-engineering.lockdown-on-tampering", true);
        tamperingCheckIntervalTicks = plugin.getConfig().getLong(
            "anti-reverse-engineering.class-tampering-check.interval-ticks", 600L);
    }

    public void start() {
        for (var p : Bukkit.getPluginManager().getPlugins()) {
            knownPluginNames.add(p.getName().toLowerCase());
        }
        if (debuggerCheckEnabled) {
            checkForDebugger();
        }
        if (tamperingCheckEnabled) {
            hashGuardedClasses();
            tamperingTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::verifyClassIntegrity, 100L, tamperingCheckIntervalTicks);
        }
        plugin.getLogger().info("Anti-Reverse-Engineering: active"
            + (debuggerDetected ? " [DEBUGGER DETECTED]" : "")
            + (lockdownMode ? " [LOCKDOWN]" : ""));
    }

    public void stop() {
        if (tamperingTask != null) { tamperingTask.cancel(); tamperingTask = null; }
        classHashes.clear();
        HandlerList.unregisterAll(this);
    }

    public boolean isLockdownMode() { return lockdownMode; }
    public boolean isDebuggerDetected() { return debuggerDetected; }
    public boolean isTamperingDetected() { return tamperingDetected; }

    public String getStatusSummary() {
        if (lockdownMode) return "LOCKDOWN - anti-cheat disabled due to tampering";
        if (debuggerDetected) return "WARNING - debugger/profiler detected";
        if (tamperingDetected) return "WARNING - class tampering detected";
        return "Secure - no threats detected";
    }

    private void checkForDebugger() {
        try {
            List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String arg : args) {
                String lower = arg.toLowerCase();
                for (String flag : SUSPICIOUS_JVM_FLAGS) {
                    if (lower.contains(flag.toLowerCase())) {
                        debuggerDetected = true;
                        plugin.getLogger().severe(
                            "[Anti-RE] Suspicious JVM flag detected: " + arg
                            + " - reverse engineering attempt likely.");
                        notifyStaff("<red>[Security] <yellow>Debugger/profiler detected: <white>" + arg);
                        if (lockdownOnTampering) {
                            enterLockdown("JVM debugger flag: " + arg);
                        }
                        return;
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[Anti-RE] Failed to check JVM args", ex);
        }
    }

    private void hashGuardedClasses() {
        for (String className : GUARDED_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className);
                String hash = computeClassHash(clazz);
                if (hash != null) {
                    classHashes.put(className, hash);
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    private void verifyClassIntegrity() {
        if (lockdownMode) return;
        for (String className : GUARDED_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className);
                String currentHash = computeClassHash(clazz);
                String storedHash = classHashes.get(className);
                if (storedHash != null && currentHash != null && !storedHash.equals(currentHash)) {
                    tamperingDetected = true;
                    plugin.getLogger().severe(
                        "[Anti-RE] Class tampering detected: " + className
                        + " hash mismatch (expected " + storedHash.substring(0, 8)
                        + " got " + currentHash.substring(0, 8) + ")");
                    notifyStaff("<red>[Security] <yellow>Class tampering detected: <white>" + className);
                    if (lockdownOnTampering) {
                        enterLockdown("Class tampering: " + className);
                    }
                    return;
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    private String computeClassHash(Class<?> clazz) {
        try {
            var className = clazz.getName().replace('.', '/') + ".class";
            var cl = clazz.getClassLoader();
            if (cl == null) return null;
            var is = cl.getResourceAsStream(className);
            if (is == null) return null;
            byte[] bytes = is.readAllBytes();
            is.close();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException | java.io.IOException e) {
            return null;
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!pluginScanEnabled) return;
        String name = event.getPlugin().getName();
        if (name.equalsIgnoreCase(plugin.getName())) return;
        if (!knownPluginNames.contains(name.toLowerCase())) {
            knownPluginNames.add(name.toLowerCase());
            scanPluginForReflection(event.getPlugin());
        }
    }

    private void scanPluginForReflection(org.bukkit.plugin.Plugin other) {
        String pkg = other.getClass().getPackageName();
        for (String guarded : guardedPackages) {
            if (pkg.startsWith(guarded) && !other.getName().equals(plugin.getName())) {
                plugin.getLogger().warning(
                    "[Anti-RE] Plugin '" + other.getName() + "' is using internal package: " + pkg);
                notifyStaff("<red>[Security] <yellow>Plugin <white>" + other.getName()
                    + " <yellow>is accessing internal LegendaryPlugin packages!");
                return;
            }
        }
    }

    private void enterLockdown(String reason) {
        if (lockdownMode) return;
        lockdownMode = true;
        plugin.getLogger().severe("[Anti-RE] ENTERING LOCKDOWN MODE: " + reason);
        plugin.getLogger().severe("[Anti-RE] Anti-cheat checks will be disabled to prevent bypass testing.");
        notifyStaff("<red>[Security] <dark_red>LOCKDOWN MODE ACTIVATED<gray>: " + reason);
        notifyStaff("<red>[Security] Anti-cheat checks disabled. Anti-DDoS remains active.");
        Bukkit.getScheduler().runTask(plugin, () -> {
            var acOpt = plugin.getModule("anticheat");
            acOpt.ifPresent(module -> {
                if (module.isEnabled()) {
                    plugin.getServer().dispatchCommand(
                        Bukkit.getConsoleSender(), "legendary module disable anticheat");
                }
            });
        });
    }

    private void notifyStaff(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("legendary.security.alerts")) {
                com.legendary.plugin.util.Text.send(p, message);
            }
        }
    }
}
