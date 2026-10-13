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
 * {@link OptimizationEngine.Settings} into each sub-system.
 *
 * PERFORMANCE UPGRADE: adds adaptive tick interval (scans more frequently
 * when the server is under stress, less frequently when healthy), caches
 * config reads to avoid repeated getConfig() calls on every tick, and
 * triggers chunk-unload hints during SEVERE lag to free memory.
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
    private final EntityAiSkipManager entityAiSkipManager;
    private final MythicMobsProtection mythicMobsProtection;
    private final ChunkHealthReporter chunkHealthReporter;
    private final JoinRampOptimizer joinRampOptimizer;
    private final RedstoneLimiterListener redstoneListener;
    private final HistoryFileWriter historyFileWriter;
    private DiscordNotifier discordNotifier;
    private ChunkHeatmapManager chunkHeatmapManager;
    private MemoryPressureMonitor memoryPressureMonitor;
    private ProjectileCleaner projectileCleaner;
    private VehicleLimiter vehicleLimiter;
    private ChunkCacheOptimizer chunkCacheOptimizer;
    private TileEntityLimiter tileEntityLimiter;

    private final Deque<HistoryPoint> memoryHistory = new ArrayDeque<>();
    private OptimizationEngine.Level currentLevel = OptimizationEngine.Level.NORMAL;
    private BukkitTask tickTask;
    private boolean enabled = false;
    private boolean simulationOverride = false;

    private long baseIntervalTicks;
    private long currentIntervalTicks;
    private boolean adaptiveInterval;

    private boolean cfgViewDistance;
    private boolean cfgMobSpawnLimit;
    private boolean cfgItemMerge;
    private boolean cfgXpMerge;
    private boolean cfgMobCap;
    private boolean cfgArmorStand;
    private boolean cfgFallingBlock;
    private boolean cfgClearEntities;
    private boolean cfgNotifyAdmins;
    private boolean cfgChunkUnloadHint;
    private int cfgChunkUnloadThreshold;
    private boolean cfgEntityAiSkip;
    private boolean cfgChunkHeatmap;
    private boolean cfgMemoryPressure;

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
        this.entityAiSkipManager = new EntityAiSkipManager();
        this.mythicMobsProtection = new MythicMobsProtection(plugin);
        this.chunkHealthReporter = new ChunkHealthReporter(plugin);
        this.joinRampOptimizer = new JoinRampOptimizer(plugin);
        this.redstoneListener = new RedstoneLimiterListener(plugin);
        this.historyFileWriter = new HistoryFileWriter(plugin, plugin.getDataFolder(),
            plugin.getConfig().getString("optimization.history.file-name", "tps-history.csv"),
            plugin.getConfig().getInt("optimization.history.max-lines", 5000));
        this.chunkHeatmapManager = new ChunkHeatmapManager(plugin);
        this.memoryPressureMonitor = new MemoryPressureMonitor(plugin);
        this.projectileCleaner = new ProjectileCleaner(plugin);
        this.vehicleLimiter = new VehicleLimiter(plugin);
        this.chunkCacheOptimizer = new ChunkCacheOptimizer(plugin);
        this.tileEntityLimiter = new TileEntityLimiter();
    }

    @Override public String id() { return "optimization"; }
    @Override public String displayName() { return "Server Optimization"; }
    @Override public int priority() { return 20; }
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void onEnable() {
        // /reload safety: unregister stale listeners before re-registering
        org.bukkit.event.HandlerList.unregisterAll(redstoneListener);
        org.bukkit.event.HandlerList.unregisterAll(joinRampOptimizer);
        org.bukkit.event.HandlerList.unregisterAll(clearEntitiesTask);

        var cfg = plugin.getConfig();
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

        cacheConfigValues();
        Bukkit.getPluginManager().registerEvents(clearEntitiesTask, plugin);

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

        baseIntervalTicks = Math.max(20L, cfg.getLong("optimization.check-interval-seconds", 5) * 20L);
        currentIntervalTicks = baseIntervalTicks;
        adaptiveInterval = cfg.getBoolean("optimization.adaptive-interval.enabled", true);
        startTickTask();

        if (cfg.getBoolean("optimization.auto-clear.enabled", false)) {
            clearEntitiesTask.startPeriodicSchedule(
                cfg.getLong("optimization.auto-clear.interval-seconds", 300),
                cfg.getInt("optimization.auto-clear.warn-seconds-before", 10));
        }
        if (cfgChunkHeatmap) {
            chunkHeatmapManager.loadConfigValues();
            chunkHeatmapManager.start();
        }
        if (cfgMemoryPressure) {
            memoryPressureMonitor.loadConfigValues();
            memoryPressureMonitor.setCallbacks(
                this::onMemoryWarning,
                this::onMemoryCritical,
                this::onMemoryRecovery);
            memoryPressureMonitor.start();
        }
        if (cfg.getBoolean("optimization.modules.projectile-cleaner", true)) {
            projectileCleaner.loadConfigValues();
            projectileCleaner.start();
        }
        if (cfg.getBoolean("optimization.modules.vehicle-limiter", true)) {
            vehicleLimiter.loadConfigValues();
            vehicleLimiter.start();
        }
        if (cfg.getBoolean("optimization.modules.chunk-cache", true)) {
            chunkCacheOptimizer.loadConfigValues();
            chunkCacheOptimizer.start();
        }
        if (cfg.getBoolean("optimization.modules.tile-entity-limiter", true)) {
            tileEntityLimiter.loadConfigValues(plugin);
        }
        enabled = true;
    }

    private void onMemoryWarning() {
        plugin.getLogger().warning("[Optimization] Memory pressure detected; reducing server view/simulation distance.");
        worldOptimizer.apply(engine.settingsFor(OptimizationEngine.Level.MODERATE));
    }

    private void onMemoryCritical() {
        plugin.getLogger().severe("[Optimization] Critical memory pressure; applying conservative server view/simulation distances.");
        OptimizationEngine.Settings emergency = engine.settingsFor(OptimizationEngine.Level.SEVERE);
        worldOptimizer.apply(emergency);
        if (cfgEntityAiSkip) {
            entityAiSkipManager.updateSettings(emergency);
            entityAiSkipManager.apply();
        }
    }

    private void onMemoryRecovery() {
        plugin.getLogger().info("[Optimization] Memory pressure recovered; restoring normal optimization settings.");
        OptimizationEngine.Settings normal = engine.settingsFor(currentLevel);
        worldOptimizer.apply(normal);
    }

    private void cacheConfigValues() {
        var cfg = plugin.getConfig();
        cfgViewDistance = cfg.getBoolean("optimization.modules.view-distance", true);
        cfgMobSpawnLimit = cfg.getBoolean("optimization.modules.mob-spawn-limit", true);
        cfgItemMerge = cfg.getBoolean("optimization.modules.item-merge", true);
        cfgXpMerge = cfg.getBoolean("optimization.modules.xp-orb-merge", true);
        cfgMobCap = cfg.getBoolean("optimization.modules.mob-cap", true);
        cfgArmorStand = cfg.getBoolean("optimization.modules.armor-stand-limit", true);
        cfgFallingBlock = cfg.getBoolean("optimization.modules.falling-block-limit", true);
        cfgClearEntities = cfg.getBoolean("optimization.modules.clear-entities", false);
        cfgNotifyAdmins = cfg.getBoolean("optimization.notify-admins-on-level-change", true);
        cfgChunkUnloadHint = cfg.getBoolean("optimization.chunk-unload-hint.enabled", true);
        cfgChunkUnloadThreshold = cfg.getInt("optimization.chunk-unload-hint.min-empty-seconds", 30);
        cfgEntityAiSkip = cfg.getBoolean("optimization.modules.entity-ai-skip", true);
        cfgChunkHeatmap = cfg.getBoolean("optimization.modules.chunk-heatmap", true);
        cfgMemoryPressure = cfg.getBoolean("optimization.modules.memory-pressure", true);
    }

    private void startTickTask() {
        if (tickTask != null) tickTask.cancel();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, currentIntervalTicks);
    }

    @Override
    public void onDisable() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        clearEntitiesTask.stopPeriodicSchedule();
        chunkHeatmapManager.stop();
        memoryPressureMonitor.stop();
        projectileCleaner.stop();
        vehicleLimiter.stop();
        chunkCacheOptimizer.stop();
        org.bukkit.event.HandlerList.unregisterAll(redstoneListener);
        org.bukkit.event.HandlerList.unregisterAll(joinRampOptimizer);
        org.bukkit.event.HandlerList.unregisterAll(clearEntitiesTask);
        joinRampOptimizer.shutdown();
        worldOptimizer.restoreAllToBase();
        entityOptimizer.restoreAllToBase();
        entityAiSkipManager.restoreAll();
        ProtectionRules.setMythicMobsProtection(null);
        enabled = false;
    }

    private void tick() {
        double tps = currentTps();
        OptimizationEngine.Level newLevel = simulationOverride ? currentLevel : stableLevel(engine.levelFor(tps), tps);
        OptimizationEngine.Settings settings = engine.settingsFor(newLevel);

        if (newLevel != currentLevel && cfgNotifyAdmins) {
            notifyAdmins(currentLevel, newLevel, tps);
            if (discordNotifier != null) discordNotifier.notifyLevelChange(currentLevel, newLevel, tps);
        }
        if (newLevel != currentLevel) lastLevelChangeAt = System.currentTimeMillis();
        currentLevel = newLevel;

        if (adaptiveInterval && !simulationOverride) {
            long newInterval = computeAdaptiveInterval(newLevel);
            if (newInterval != currentIntervalTicks) {
                currentIntervalTicks = newInterval;
                startTickTask();
            }
        }

        if (cfgViewDistance) worldOptimizer.apply(settings);
        if (cfgMobSpawnLimit) entityOptimizer.apply(settings);
        if (cfgItemMerge) itemMergeTask.updateSettings(settings);
        if (cfgXpMerge) xpOrbMergeTask.updateSettings(settings);
        if (cfgMobCap) mobCapEnforcer.updateLevel(newLevel);
        if (cfgArmorStand) armorStandLimiter.updateLevel(newLevel);
        if (cfgFallingBlock) fallingBlockLimiter.updateLevel(newLevel);
        entitySweepTask.run(cfgItemMerge, cfgXpMerge, cfgMobCap, cfgArmorStand, cfgFallingBlock);
        if (cfgClearEntities) { clearEntitiesTask.updateLevel(newLevel); clearEntitiesTask.runScheduled(); }
        if (cfgEntityAiSkip) {
            entityAiSkipManager.updateSettings(settings);
            entityAiSkipManager.apply();
        }

        if (cfgChunkUnloadHint && newLevel == OptimizationEngine.Level.SEVERE) {
            suggestChunkUnloads();
        }

        tileEntityLimiter.updateLevel(newLevel);

        plugin.getMetrics().gauge("optimization.tps").set(tps);
        plugin.getMetrics().gauge("optimization.level").set(newLevel.ordinal());

        recordHistory(tps, newLevel);
    }

    private long computeAdaptiveInterval(OptimizationEngine.Level level) {
        // Widen interval as lag gets worse — running mitigation more often
        // during severe lag creates a feedback loop that makes lag worse.
        return switch (level) {
            case NORMAL -> baseIntervalTicks;
            case MILD -> baseIntervalTicks;
            case MODERATE -> Math.max(20L, baseIntervalTicks * 3 / 2);
            case SEVERE -> Math.max(20L, baseIntervalTicks * 2);
        };
    }

    private final java.util.Map<java.util.UUID, java.util.concurrent.ConcurrentMap<Long, Long>> chunkEmptySince =
        new java.util.concurrent.ConcurrentHashMap<>();
    private OptimizationEngine.Level candidateLevel = OptimizationEngine.Level.NORMAL;
    private long candidateSince;
    private long lastLevelChangeAt;

    private void suggestChunkUnloads() {
        long now = System.currentTimeMillis();
        long minEmptyMs = cfgChunkUnloadThreshold * 1000L;
        int renderDistSq = 128 * 128; // skip chunks within render distance of any player
        int unloaded = 0;

        for (var world : plugin.getServer().getWorlds()) {
            var players = world.getPlayers();
            var emptySince = chunkEmptySince.computeIfAbsent(world.getUID(), ignored -> new java.util.concurrent.ConcurrentHashMap<>());
            java.util.Set<Long> loadedKeys = new java.util.HashSet<>();
            for (var chunk : world.getLoadedChunks()) {
                long chunkKey = chunk.getChunkKey();
                loadedKeys.add(chunkKey);
                boolean isEmpty = chunk.getEntities().length == 0 && chunk.getTileEntities().length == 0;
                if (isEmpty) {
                    emptySince.computeIfAbsent(chunkKey, k -> now);
                } else {
                    emptySince.remove(chunkKey);
                    continue;
                }
                if (now - emptySince.get(chunkKey) < minEmptyMs) continue;

                boolean nearPlayer = false;
                int cx = chunk.getX() << 4;
                int cz = chunk.getZ() << 4;
                for (var p : players) {
                    if (p.getLocation().getBlockX() >= cx - 128 && p.getLocation().getBlockX() < cx + 144
                        && p.getLocation().getBlockZ() >= cz - 128 && p.getLocation().getBlockZ() < cz + 144) {
                        nearPlayer = true;
                        break;
                    }
                }
                if (nearPlayer) continue;

                if (world.unloadChunk(chunk)) unloaded++;
                emptySince.remove(chunkKey);
                if (unloaded >= 50) break;
            }
            emptySince.keySet().removeIf(key -> !loadedKeys.contains(key));
            if (unloaded >= 50) break;
        }
        if (unloaded > 0) {
            plugin.getLogger().info("[Optimization] Unloaded " + unloaded + " empty chunks (empty for >" + cfgChunkUnloadThreshold + "s, no players nearby) to free memory (SEVERE lag).");
        }
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
        while (memoryHistory.size() > 288) memoryHistory.removeFirst();
        historyFileWriter.appendAsync(now, tps, level.toString());
    }

    private double currentTps() {
        double averageTickMs = Bukkit.getAverageTickTime();
        return averageTickMs > 0 ? Math.min(20.0, 1000.0 / averageTickMs) : 20.0;
    }

    private OptimizationEngine.Level stableLevel(OptimizationEngine.Level desired, double tps) {
        long now = System.currentTimeMillis();
        if (currentLevel == OptimizationEngine.Level.SEVERE && tps <= 18.0) {
            return OptimizationEngine.Level.SEVERE;
        }
        if (desired != candidateLevel) {
            candidateLevel = desired;
            candidateSince = now;
        }
        long requiredStableMs = desired == OptimizationEngine.Level.SEVERE && tps < 14.0
            ? 30_000L
            : desired.ordinal() < currentLevel.ordinal() ? 60_000L : 0L;
        if (now - candidateSince < requiredStableMs || now - lastLevelChangeAt < 60_000L) {
            return currentLevel;
        }
        return desired;
    }

    private int resolveDefaultDistance(java.util.Map<String, Integer> detectedMap) {
        return detectedMap.values().stream()
            .filter(v -> v != null && v > 0)
            .findFirst()
            .orElse(10);
    }

    public OptimizationEngine.Level getCurrentLevel() { return currentLevel; }
    public double getLastTps() { return currentTps(); }
    public List<HistoryPoint> getMemoryHistory() { synchronized (memoryHistory) { return List.copyOf(memoryHistory); } }
    public ChunkHealthReporter getChunkHealthReporter() { return chunkHealthReporter; }
    public ChunkHeatmapManager getChunkHeatmapManager() { return chunkHeatmapManager; }
    public MemoryPressureMonitor getMemoryPressureMonitor() { return memoryPressureMonitor; }
    public ProjectileCleaner getProjectileCleaner() { return projectileCleaner; }
    public VehicleLimiter getVehicleLimiter() { return vehicleLimiter; }
    public ChunkCacheOptimizer getChunkCacheOptimizer() { return chunkCacheOptimizer; }
    public TileEntityLimiter getTileEntityLimiter() { return tileEntityLimiter; }

    public void simulate(OptimizationEngine.Level level) {
        simulationOverride = true;
        currentLevel = level;
        tick();
    }

    public void clearSimulation() {
        simulationOverride = false;
    }
}
