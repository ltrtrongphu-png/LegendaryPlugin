package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import com.legendary.plugin.modules.anticheat.checks.*;
import com.legendary.plugin.modules.anticheat.checks.VelocityCheck;
import com.legendary.plugin.modules.anticheat.checks.FlyBoostCheck;
import com.legendary.plugin.modules.anticheat.checks.BadPacketsCheck;
import com.legendary.plugin.modules.anticheat.checks.FastEatCheck;
import com.legendary.plugin.modules.anticheat.checks.AntiVoidCheck;
import com.legendary.plugin.modules.anticheat.listeners.*;
import com.legendary.plugin.modules.database.DatabaseManager;
import com.legendary.plugin.modules.replay.ReplayModule;
import com.legendary.plugin.integrations.BungeeIntegration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates every anti-cheat feature ported from BaB: all 46 checks,
 * violation/escalation/ban/alert plumbing, TPS compensation, and the
 * optional ProtocolLib-based packet checks. This is the single entry
 * point every {@link Check} talks back to via {@link #flag}.
 */
public final class AnticheatModule implements Module {

    private final LegendaryPlugin plugin;
    private final ViolationManager violationManager;
    private final EscalationManager escalationManager;
    private final AlertManager alertManager;
    private final BanExecutor banExecutor;
    private final TpsMonitor tpsMonitor;
    private final CheckRegistry checkRegistry;
    private final DatabaseManager databaseManager;
    private final WatchManager watchManager;
    private final BungeeIntegration bungeeIntegration;
    private final PlayerTrustManager trustManager;
    private final CheckProfile checkProfile;
    private HackProfileManager hackProfileManager;
    private ThreatActionManager threatActionManager;
    private BypassGuard bypassGuard;

    private final RaidAlertListener raidAlertListener;
    private final AntiSpamListener antiSpamListener;
    private final ClientBrandListener clientBrandListener;
    private InvalidPacketCheck invalidPacketCheck;   // ProtocolLib-only
    private PacketFloodCheck packetFloodCheck;         // ProtocolLib-only
    private DisablerCheck disablerCheck;               // ProtocolLib-only
    private AntiHungerCheck antiHungerCheck;           // has a periodic task

    private final Map<String, String> escalationConfigCache = new ConcurrentHashMap<>();

    private boolean enabled = false;

    public AnticheatModule(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.violationManager = new ViolationManager(plugin);
        this.escalationManager = new EscalationManager(plugin);
        this.alertManager = new AlertManager(plugin);
        this.banExecutor = new BanExecutor(plugin);
        this.tpsMonitor = new TpsMonitor(plugin);
        this.checkRegistry = new CheckRegistry(plugin);
        this.databaseManager = new DatabaseManager(plugin);
        this.watchManager = new WatchManager(plugin);
        this.bungeeIntegration = new BungeeIntegration(plugin);
        this.trustManager = new PlayerTrustManager(plugin);
        this.checkProfile = new CheckProfile(plugin);
        this.hackProfileManager = new HackProfileManager(violationManager, checkRegistry);
        this.threatActionManager = new ThreatActionManager(plugin, this, hackProfileManager);
        this.bypassGuard = new BypassGuard(plugin);
        this.raidAlertListener = new RaidAlertListener(plugin);
        this.antiSpamListener = new AntiSpamListener(plugin, this);
        this.clientBrandListener = new ClientBrandListener(plugin);

        checkRegistry.register(new KillAuraCheck(this));
        checkRegistry.register(new AimAssistCheck(this));
        checkRegistry.register(new ImpossibleHitCheck(this));
        checkRegistry.register(new ScaffoldCheck(this));
        checkRegistry.register(new SpiderCheck(this));
        checkRegistry.register(new AutoTotemCheck(this));
        checkRegistry.register(new AutoArmorCheck(this));
        checkRegistry.register(new BlockReachCheck(this));
        checkRegistry.register(new ContainerReachCheck(this));
        checkRegistry.register(new FastUseCheck(this));
        checkRegistry.register(new FastInteractCheck(this));
        checkRegistry.register(new FastBreakCheck(this));
        checkRegistry.register(new InventoryActionCheck(this));
        checkRegistry.register(new KnockbackCheck(this));
        checkRegistry.register(new MovementCheck(this));
        checkRegistry.register(new NoClipCheck(this));
        checkRegistry.register(new NoFallCheck(this));
        checkRegistry.register(new NoSlowCheck(this));
        checkRegistry.register(new NoSwingCheck(this));
        checkRegistry.register(new RotationCheck(this));
        checkRegistry.register(new StepCheck(this));
        checkRegistry.register(new TimerCheck(this));
        checkRegistry.register(new XrayCheck(this));
        checkRegistry.register(new ElytraFlightCheck(this));
        checkRegistry.register(new InstaBreakCheck(this));
        checkRegistry.register(new JesusCheck(this));
        checkRegistry.register(new SurroundCheck(this));
        checkRegistry.register(new AutoToolCheck(this));
        checkRegistry.register(new ShulkerNestingCheck(this));
        checkRegistry.register(new ChestAuraCheck(this));
        checkRegistry.register(new CriticalsCheck(this));
        checkRegistry.register(new FastClimbCheck(this));
        checkRegistry.register(new JumpHeightCheck(this));
        checkRegistry.register(new BlinkCheck(this));
        checkRegistry.register(new GUIMoveCheck(this));
        checkRegistry.register(new BowSpamCheck(this));
        checkRegistry.register(new VelocityCheck(this));
        checkRegistry.register(new FlyBoostCheck(this));
        checkRegistry.register(new BadPacketsCheck(this));
        checkRegistry.register(new FastEatCheck(this));
        checkRegistry.register(new AntiVoidCheck(this));
        checkRegistry.register(new FastPlaceCheck(this));
        checkRegistry.register(new PhaseCheck(this));
        checkRegistry.register(new GlideCheck(this));
        checkRegistry.register(new StrafeCheck(this));
        checkRegistry.register(new AntiHungerCheck(this));
        antiHungerCheck = (AntiHungerCheck) checkRegistry.get("antihunger").orElse(null);
    }

    @Override public String id() { return "anticheat"; }
    @Override public String displayName() { return "Anti-Cheat"; }
    @Override public int priority() { return 50; }
    @Override public String[] dependsOn() { return new String[] {"replay"}; }
    @Override public boolean isEnabled() { return enabled; }

    public LegendaryPlugin getPlugin() { return plugin; }
    public ViolationManager getViolationManager() { return violationManager; }
    public EscalationManager getEscalationManager() { return escalationManager; }
    public AlertManager getAlertManager() { return alertManager; }
    public BanExecutor getBanExecutor() { return banExecutor; }
    public TpsMonitor getTpsMonitor() { return tpsMonitor; }
    public CheckRegistry getCheckRegistry() { return checkRegistry; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public WatchManager getWatchManager() { return watchManager; }
    public PlayerTrustManager getTrustManager() { return trustManager; }
    public CheckProfile getCheckProfile() { return checkProfile; }
    public HackProfileManager getHackProfileManager() { return hackProfileManager; }
    public ThreatActionManager getThreatActionManager() { return threatActionManager; }
    public BypassGuard getBypassGuard() { return bypassGuard; }

    @Override
    public void onEnable() {
        databaseManager.connectAsync(() ->
            plugin.getLogger().info("Anti-cheat database ready (" + (plugin.getConfig().getString("anticheat.database.type", "sqlite")) + ")."));

        violationManager.loadConfigValues();
        violationManager.start();
        Bukkit.getPluginManager().registerEvents(violationManager, plugin);
        escalationManager.loadConfigValues();
        Bukkit.getPluginManager().registerEvents(escalationManager, plugin);
        alertManager.loadConfigValues();
        banExecutor.loadConfigValues();
        checkProfile.loadConfigValues();
        plugin.getLogger().info("Anti-cheat profile: " + checkProfile.getActivePreset().getConfigName()
            + " (weight x" + checkProfile.getWeightMultiplier() + ")");
        if (plugin.getConfig().getBoolean("anticheat.bungee-sync.enabled", false)) {
            bungeeIntegration.register();
            banExecutor.setBungeeIntegration(bungeeIntegration);
            plugin.getLogger().info("Bungee/Velocity ban-sync enabled.");
        }
        Bukkit.getPluginManager().registerEvents(watchManager, plugin);
        trustManager.loadConfigValues();
        trustManager.start();
        Bukkit.getPluginManager().registerEvents(trustManager, plugin);
        tpsMonitor.loadConfigValues();
        tpsMonitor.start();
        hackProfileManager.setTrustManager(trustManager);
        threatActionManager.loadConfigValues();
        threatActionManager.start();
        bypassGuard.loadConfigValues();
        bypassGuard.start();

        checkRegistry.enableAllConfigured();

        if (antiHungerCheck != null && antiHungerCheck.isEnabled()) {
            antiHungerCheck.start();
        }

        raidAlertListener.loadConfigValues();
        Bukkit.getPluginManager().registerEvents(raidAlertListener, plugin);
        antiSpamListener.loadConfigValues();
        Bukkit.getPluginManager().registerEvents(antiSpamListener, plugin);
        clientBrandListener.loadConfigValues();
        Bukkit.getPluginManager().registerEvents(clientBrandListener, plugin);

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            if (plugin.getConfig().getBoolean("anticheat.checks.invalidpacket.enabled", true)) {
                invalidPacketCheck = new InvalidPacketCheck(plugin, this);
                invalidPacketCheck.register();
            }
            if (plugin.getConfig().getBoolean("anticheat.checks.packetflood.enabled", true)) {
                packetFloodCheck = new PacketFloodCheck(plugin, this);
                packetFloodCheck.loadConfigValues();
                packetFloodCheck.register();
            }
            if (plugin.getConfig().getBoolean("anticheat.checks.disabler.enabled", true)) {
                disablerCheck = new DisablerCheck(plugin, this);
                disablerCheck.loadConfigValues();
                disablerCheck.register();
            }
        } else {
            plugin.getLogger().warning("ProtocolLib not found - InvalidPacket, PacketFlood and Disabler checks are disabled.");
        }
        enabled = true;
    }

    @Override
    public void onDisable() {
        violationManager.stop();
        org.bukkit.event.HandlerList.unregisterAll(violationManager);
        org.bukkit.event.HandlerList.unregisterAll(escalationManager);
        tpsMonitor.stop();
        if (antiHungerCheck != null) { antiHungerCheck.stop(); }
        checkRegistry.disableAll();
        org.bukkit.event.HandlerList.unregisterAll(raidAlertListener);
        org.bukkit.event.HandlerList.unregisterAll(antiSpamListener);
        org.bukkit.event.HandlerList.unregisterAll(clientBrandListener);
        clientBrandListener.unregister();
        if (invalidPacketCheck != null) { invalidPacketCheck.unregister(); invalidPacketCheck = null; }
        if (packetFloodCheck != null) { packetFloodCheck.unregister(); packetFloodCheck = null; }
        if (disablerCheck != null) { disablerCheck.unregister(); disablerCheck = null; }
        databaseManager.shutdown();
        org.bukkit.event.HandlerList.unregisterAll(watchManager);
        watchManager.clear();
        trustManager.stop();
        org.bukkit.event.HandlerList.unregisterAll(trustManager);
        threatActionManager.stop();
        bypassGuard.stop();
        bungeeIntegration.unregister();
        enabled = false;
    }

    /** Called by every Check subclass when it detects a violation. */
    public void flag(Check check, Player player, double weight, String detail) {
        double profileMultiplier = checkProfile.getWeightMultiplier();
        double trustMultiplier = trustManager.getWeightMultiplier(player);
        double tpsSensitivity = tpsMonitor.getSensitivityMultiplier();
        double adjustedWeight = weight * trustMultiplier * profileMultiplier * tpsSensitivity;

        ViolationContext ctx = ViolationContext.builder(check, player, adjustedWeight, detail)
            .ping(player.getPing())
            .tps(tpsMonitor.getTps())
            .build();

        flagRaw(player, check.getId(), adjustedWeight, detail);
        hackProfileManager.recordFlag(player.getUniqueId(), check.getId());
        threatActionManager.onFlag(player.getUniqueId());
        bypassGuard.onFlagCheck(player, check.getId());
        trustManager.onViolation(player, weight);
        maybeRecordReplay(player, check.getId());
        plugin.getMetrics().counter("anticheat.flags.total").increment();
        plugin.getMetrics().counter("anticheat.flags." + check.getId()).increment();
        if (ctx.isLagSuspect()) {
            plugin.getMetrics().counter("anticheat.flags.lag-suspect").increment();
        }
    }

    /** Entry point for packet-level (non-Check-subclass) detectors like InvalidPacketCheck. */
    public void flagRaw(Player player, String checkId, double weight, String detail) {
        double vl = violationManager.addViolation(player, checkId, weight);
        String actions = escalationConfigCache.computeIfAbsent(checkId,
            id -> plugin.getConfig().getString("anticheat.checks." + id + ".escalation", "alert,alert,alert,kick,ban"));
        String display = checkRegistry.get(checkId).map(Check::getDisplayName).orElse(checkId);
        List<String> activeHacks = hackProfileManager.getActiveHacks(player);
        alertManager.alertViolation(player, display, vl, 0, activeHacks);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[AC-debug] " + player.getName() + " " + checkId + " " + detail);
        }
        databaseManager.recordViolationAsync(player.getUniqueId().toString(), player.getName(), checkId, weight, vl, detail);
        watchManager.notifyWatchers(player, display, vl, detail);
        escalationManager.escalate(player, checkId, display, actions);
    }

    private void maybeRecordReplay(Player player, String checkId) {
        plugin.getModule("replay")
            .filter(m -> m instanceof ReplayModule)
            .map(m -> (ReplayModule) m)
            .ifPresent(replay -> replay.saveClipOnFlag(player, checkId));
    }
}
