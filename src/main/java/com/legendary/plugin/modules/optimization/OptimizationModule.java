package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import com.legendary.plugin.integrations.DiscordNotifier;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Ties together every SmartOptimizer feature: watches the server TPS,
 * derives an {@link OptimizationEngine.Level}, and pushes the resulting
 * {@link OptimizationEngine.Settings} into each sub-system. Ported /
 * modularized from SmartOptimizerPlugin's monolithic onEnable().
 */
public final class OptimizationModule implements Module {

    public record HistoryPoint(long timestamp, double tps, OptimizationEngine.Level level) {}

    private final LegendaryPlugin plugin;
    private final OptimizationEngine engine = new OptimizationEngine();
    private final WorldOptimizer worldOptimizer;
    private final EntityOptimizer entityOptimizer;
    private final ItemMergeTask itemMergeTask;
    private final XpOrbMergeTask xpOrbMergeTask;
    private final MobCapEnforcer mobCapEnforcer;
    private final ArmorStandLimiter armorStandLimiter;
    private final FallingBlockLimiter fallingBlockLimiter;
    private final ClearEntitiesTask clearEntitiesTask;
    private final EntitySweepTask entitySweepTask;
    private final MythicMobsProtection mythicMobsProtection;
    private final ChunkHealthReporter chunkHealthReporter;
    private final JoinRampOptimizer joinRampOptimizer;
    private final HopperThrottleListener hopperListener;
    private final RedstoneLimiterListener redstoneListener;
    private final HistoryFileWriter historyFileWriter;
    private DiscordNotifier discordNotifier;

    private final Deque<HistoryPoint> memoryHistory = new ArrayDeque<>();
    private OptimizationEngine.Level currentLevel = OptimizationEngine.Level.NORMAL;
    private BukkitTask tickTask;
    private boolean enabled = false;
    private boolean simulationOverride = false;

    public OptimizationModule(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.worldOptimizer = new WorldOptimizer(plugin);
        this.entityOptimizer = new EntityOptimizer(plugin);
        this.itemMergeTask = new ItemMergeTask(plugin);
        this.xpOrbMergeTask = new XpOrbMergeTask(plugin);
        this.mobCapEnforcer = new MobCapEnforcer(plugin);
        this.armorStandLimiter = new ArmorStandLimiter(plugin);
        this.fallingBlockLimiter = new FallingBlockLimiter(plugin);
        this.clearEntitiesTask = new ClearEntitiesTask(plugin);
        this.entitySweepTask = new EntitySweepTask(plugin, itemMergeTask, xpOrbMergeTask, mobCapEnforcer, armorStandLimiter, fallingBlockLimiter);
        this.mythicMobsProtection = new MythicMobsProtection(plugin);
        this.chunkHealthReporter = new ChunkHealthReporter(plugin);
        this.joinRampOptimizer = new JoinRampOptimizer(plugin);
        this.hopperListener = new HopperThrottleListener(plugin);
        this.redstoneListener = new RedstoneLimiterListener(plugin);
        this.historyFileWriter = new HistoryFileWriter(plugin, plugin.getDataFolder(),
            plugin.getConfig().getString("optimization.history.file-name", "tps-history.csv"),
            plugin.getConfig().getInt("optimization.history.max-lines", 5000));
    }

    @Override public String id() { return "optimization"; }
    @Override public String displayName() { return "Server Optimization"; }
    @Override public int priority() { return 20; }
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void onEnable() {
        var cfg = plugin.getConfig();
        // Detect the real per-world view/sim distance FIRST - engine.configure() needs an
        // actual positive number, not the config's -1 ("auto") sentinel passed through
        // unresolved. Passing -1 straight into World#setViewDistance/#setSimulationDistance
        // is exactly what caused the "View/Simulation distance -1 is out of range of [2, 32]"
        // exception spam in production (2026-09-19 incident) - fixed here at the source, with
        // OptimizationEngine and WorldOptimizer each also clamping defensively as backup.
        worldOptimizer.detectBaseDistancesIfNeeded();
        entityOptimizer.recaptureAll();
        mythicMobsProtection.init();
        ProtectionRules.setMythicMobsProtection(mythicMobsProtection);

        int configuredView = cfg.getInt("optimization.base-view-distance", -1);
        int configuredSim = cfg.getInt("optimization.base-simulation-distance", -1);
        int resolvedView = configuredView > 0 ? configuredView : resolveDefaultDistance(worldOptimizer.getBaseViewDistanceMap());
        int resolvedSim = configuredSim > 0 ? configuredSim : resolveDefaultDistance(worldOptimizer.getBaseSimDistanceMap());

        engine.configure(
            cfg.getDouble("optimization.thresholds.mild-tps", 19.3),
            cfg.getDouble("optimization.thresholds.moderate-tps", 17.0),
            cfg.getDouble("optimization.thresholds.severe-tps", 14.0),
            resolvedView, resolvedSim);

        if (cfg.getBoolean("optimization.modules.hopper-throttle", true)) {
            Bukkit.getPluginManager().registerEvents(hopperListener, plugin);
        }
        if (cfg.getBoolean("optimization.modules.redstone-limiter", true)) {
            redstoneListener.loadConfigValues();
            Bukkit.getPluginManager().registerEvents(redstoneListener, plugin);
        }
        if (cfg.getBoolean("optimization.modules.join-ramp", true)) {
            Bukkit.getPluginManager().registerEvents(joinRampOptimizer, plugin);
            joinRampOptimizer.setWorldOptimizer(worldOptimizer);
        }

        if (cfg.getBoolean("optimization.discord.enabled", false)) {
            discordNotifier = new DiscordNotifier(plugin, cfg.getString("optimization.discord.webhook-url", ""));
        }

        long intervalTicks = Math.max(20L, cfg.getLong("optimization.check-interval-seconds", 5) * 20L);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, intervalTicks);

        if (cfg.getBoolean("optimization.auto-clear.enabled", false)) {
            clearEntitiesTask.startPeriodicSchedule(
                cfg.getLong("optimization.auto-clear.interval-seconds", 300),
                cfg.getInt("optimization.auto-clear.warn-seconds-before", 10));
        }
        enabled = true;
    }

    @Override
    public void onDisable() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        clearEntitiesTask.stopPeriodicSchedule();
        org.bukkit.event.HandlerList.unregisterAll(hopperListener);
        org.bukkit.event.HandlerList.unregisterAll(redstoneListener);
        org.bukkit.event.HandlerList.unregisterAll(joinRampOptimizer);
        joinRampOptimizer.shutdown();
        worldOptimizer.restoreAllToBase();
        entityOptimizer.restoreAllToBase();
        ProtectionRules.setMythicMobsProtection(null);
        enabled = false;
    }

    private void tick() {
        double tps = currentTps();
        OptimizationEngine.Level newLevel = simulationOverride ? currentLevel : engine.levelFor(tps);
        OptimizationEngine.Settings settings = engine.settingsFor(newLevel);

        if (newLevel != currentLevel && plugin.getConfig().getBoolean("optimization.notify-admins-on-level-change", true)) {
            notifyAdmins(currentLevel, newLevel, tps);
            if (discordNotifier != null) discordNotifier.notifyLevelChange(currentLevel, newLevel, tps);
        }
        currentLevel = newLevel;

        var cfg = plugin.getConfig();
        boolean itemMergeOn = cfg.getBoolean("optimization.modules.item-merge", true);
        boolean xpMergeOn = cfg.getBoolean("optimization.modules.xp-orb-merge", true);
        boolean mobCapOn = cfg.getBoolean("optimization.modules.mob-cap", true);
        boolean armorStandOn = cfg.getBoolean("optimization.modules.armor-stand-limit", true);
        boolean fallingBlockOn = cfg.getBoolean("optimization.modules.falling-block-limit", true);

        if (cfg.getBoolean("optimization.modules.view-distance", true)) worldOptimizer.apply(settings);
        if (cfg.getBoolean("optimization.modules.mob-spawn-limit", true)) entityOptimizer.apply(settings);
        if (itemMergeOn) itemMergeTask.updateSettings(settings);
        if (xpMergeOn) xpOrbMergeTask.updateSettings(settings);
        if (mobCapOn) mobCapEnforcer.updateLevel(newLevel);
        if (armorStandOn) armorStandLimiter.updateLevel(newLevel);
        if (fallingBlockOn) fallingBlockLimiter.updateLevel(newLevel);
        // Single combined chunk/entity scan for all five sub-systems above - see EntitySweepTask's class doc.
        entitySweepTask.run(itemMergeOn, xpMergeOn, mobCapOn, armorStandOn, fallingBlockOn);
        if (cfg.getBoolean("optimization.modules.clear-entities", true)) { clearEntitiesTask.updateLevel(newLevel); clearEntitiesTask.runScheduled(); }
        hopperListener.updateSettings(settings);

        recordHistory(tps, newLevel);
    }

    private void notifyAdmins(OptimizationEngine.Level from, OptimizationEngine.Level to, double tps) {
        String msg = "<gray>[<gradient:#38BDF8:#A855F7>Legendary</gradient>] <white>TPS "
            + String.format("%.1f", tps) + " - level " + from + " -> <yellow>" + to;
        for (var player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("legendary.admin")) {
                com.legendary.plugin.util.Text.send(player, msg);
            }
        }
        Bukkit.getConsoleSender().sendMessage(com.legendary.plugin.util.Text.of(msg));
    }

    private void recordHistory(double tps, OptimizationEngine.Level level) {
        long now = System.currentTimeMillis();
        memoryHistory.addLast(new HistoryPoint(now, tps, level));
        while (memoryHistory.size() > 288) memoryHistory.removeFirst(); // ~24h at 5min intervals
        historyFileWriter.appendAsync(now, tps, level.toString());
    }

    private double currentTps() {
        try {
            double[] tps = Bukkit.getTPS();
            return tps.length > 0 ? Math.min(20.0, tps[0]) : 20.0;
        } catch (Throwable t) {
            return 20.0;
        }
    }

    /** Picks a real, positive default distance from the detected per-world map (falls back to 10, never -1/0). */
    private int resolveDefaultDistance(java.util.Map<String, Integer> detectedMap) {
        return detectedMap.values().stream()
            .filter(v -> v != null && v > 0)
            .findFirst()
            .orElse(10);
    }

    // ---- exposed for /legendary command ----
    public OptimizationEngine.Level getCurrentLevel() { return currentLevel; }
    public double getLastTps() { return currentTps(); }
    public List<HistoryPoint> getMemoryHistory() { synchronized (memoryHistory) { return List.copyOf(memoryHistory); } }
    public ChunkHealthReporter getChunkHealthReporter() { return chunkHealthReporter; }

    public void simulate(OptimizationEngine.Level level) {
        simulationOverride = true;
        currentLevel = level;
        tick();
    }

    public void clearSimulation() {
        simulationOverride = false;
    }
}
