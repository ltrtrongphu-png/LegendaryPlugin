package com.legendary.plugin.modules.security;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Anti-reverse-engineering manager. Detects debuggers/profilers attached to
 * the plugin JAR on disk and reports configured suspicious JVM flags.
 * It does not claim to detect in-memory bytecode changes or reflection.
 */
public final class AntiReverseEngineeringManager implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<String, String> classHashes = new ConcurrentHashMap<>();
    private volatile boolean lockdownMode = false;
    private volatile boolean debuggerDetected = false;
    private volatile boolean tamperingDetected = false;

    private boolean debuggerCheckEnabled;
    private boolean tamperingCheckEnabled;
    private boolean lockdownOnTampering;
    private long tamperingCheckIntervalTicks;
    private BukkitTask tamperingTask;
    private List<String> whitelistedJvmFlagPrefixes = List.of();

    private static final List<String> SUSPICIOUS_JVM_FLAGS = List.of(
        "-agentlib", "-Xrunhprof", "-javaagent", "-Xdebug", "-agentpath", "-Xrunjdwp");

    public AntiReverseEngineeringManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        debuggerCheckEnabled = plugin.getConfig().getBoolean("anti-reverse-engineering.debugger-detection.enabled", true);
        tamperingCheckEnabled = plugin.getConfig().getBoolean("anti-reverse-engineering.class-tampering-check.enabled", true);
        lockdownOnTampering = plugin.getConfig().getBoolean("anti-reverse-engineering.lockdown-on-tampering", false);
        tamperingCheckIntervalTicks = plugin.getConfig().getLong(
            "anti-reverse-engineering.class-tampering-check.interval-ticks", 600L);
        whitelistedJvmFlagPrefixes = List.copyOf(plugin.getConfig()
            .getStringList("anti-reverse-engineering.debugger-detection.whitelisted-flag-prefixes"));
    }

    public void start() {
        if (debuggerCheckEnabled) {
            checkForDebugger();
        }
        if (tamperingCheckEnabled) {
            hashPluginJar();
            tamperingTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::verifyClassIntegrity, 100L, tamperingCheckIntervalTicks);
        }
        plugin.getLogger().info("Integrity Check: active"
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

    public boolean unlock() {
        if (!lockdownMode) return false;
        lockdownMode = false;
        tamperingDetected = false;
        hashPluginJar();
        plugin.getModuleManager().enable("anticheat");
        plugin.getLogger().warning("[Integrity Check] Lockdown was manually cleared by an administrator.");
        return true;
    }

    public String getStatusSummary() {
        if (lockdownMode) return "LOCKDOWN - explicitly configured integrity response is active";
        if (debuggerDetected) return "WARNING - JVM instrumentation flag detected";
        if (tamperingDetected) return "WARNING - class tampering detected";
        return "Integrity check active; it does not inspect in-memory classes";
    }

    private void checkForDebugger() {
        try {
            List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String arg : args) {
                String lower = arg.toLowerCase();
                for (String flag : SUSPICIOUS_JVM_FLAGS) {
                    boolean whitelisted = whitelistedJvmFlagPrefixes.stream()
                        .anyMatch(prefix -> !prefix.isBlank() && lower.startsWith(prefix.toLowerCase()));
                    if (!whitelisted && lower.contains(flag.toLowerCase())) {
                        debuggerDetected = true;
                        plugin.getLogger().warning("[Integrity Check] JVM instrumentation/debug flag detected: " + arg
                            + ". Monitoring/profiling agents are common on hosted servers; no lockdown was triggered.");
                        notifyStaff("<yellow>[Integrity Check] JVM instrumentation/debug flag detected; "
                            + "no lockdown was triggered.");
                        break;
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[Anti-RE] Failed to check JVM args", ex);
        }
    }

    private void hashPluginJar() {
        String hash = computePluginJarHash();
        if (hash == null) {
            plugin.getLogger().warning("[Integrity Check] Plugin artifact is not a regular JAR; disk integrity monitoring is unavailable.");
            return;
        }
        classHashes.put("plugin-jar", hash);
    }

    private void verifyClassIntegrity() {
        if (lockdownMode) return;
        String currentHash = computePluginJarHash();
        String storedHash = classHashes.get("plugin-jar");
        if (storedHash != null && currentHash != null && !storedHash.equals(currentHash)) {
            tamperingDetected = true;
            plugin.getLogger().severe("[Integrity Check] Plugin JAR on disk changed after startup.");
            notifyStaff("<red>[Integrity Check] Plugin JAR on disk changed after startup.");
            if (lockdownOnTampering) enterLockdown("Plugin JAR on-disk checksum changed");
        }
    }

    private String computePluginJarHash() {
        try {
            var source = plugin.getClass().getProtectionDomain().getCodeSource();
            if (source == null) return null;
            Path artifact = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(artifact)) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(artifact)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) md.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | java.io.IOException | java.net.URISyntaxException e) {
            return null;
        }
    }

    private void enterLockdown(String reason) {
        if (lockdownMode) return;
        lockdownMode = true;
        plugin.getLogger().severe("[Integrity Check] Entering explicitly configured lockdown: " + reason);
        plugin.getLogger().severe("[Integrity Check] Anti-cheat module will be disabled by configuration.");
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
        Runnable notify = () -> Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.hasPermission("legendary.security.alerts"))
            .forEach(p -> com.legendary.plugin.util.Text.send(p, message));
        if (Bukkit.isPrimaryThread()) notify.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, notify);
    }
}
