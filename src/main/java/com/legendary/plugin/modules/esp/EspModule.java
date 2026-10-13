package com.legendary.plugin.modules.esp;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import org.bukkit.Bukkit;

/**
 * Aggregates every ESP / anti-X-ray feature from the old AntiESPUltimate
 * and BaB.EspProtectionManager plugins into one {@link Module}:
 * player-visibility hiding, block obfuscation, capped view distance and
 * free-cam detection.
 */
public final class EspModule implements Module {

    private final LegendaryPlugin plugin;
    private final PlayerVisibilityManager playerVisibility;
    private final ViewDistanceManager viewDistance;
    private final AntiFreeCamManager antiFreeCam;
    private BlockObfuscationManager blockObfuscation; // only constructed if ProtocolLib is present
    private final EntityOcclusionManager entityOcclusion;
    private final PaperAntiXraySupport paperAntiXraySupport;
    private boolean enabled = false;

    public EspModule(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.playerVisibility = new PlayerVisibilityManager(plugin);
        this.viewDistance = new ViewDistanceManager(plugin);
        this.antiFreeCam = new AntiFreeCamManager(plugin);
        this.entityOcclusion = new EntityOcclusionManager(plugin);
        this.paperAntiXraySupport = new PaperAntiXraySupport(plugin);
    }

    public PaperAntiXraySupport getPaperAntiXraySupport() { return paperAntiXraySupport; }

    @Override public String id() { return "esp"; }
    @Override public String displayName() { return "ESP / Anti-X-ray Protection"; }
    @Override public int priority() { return 10; }
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void onEnable() {
        playerVisibility.loadConfigValues();
        playerVisibility.start();

        viewDistance.loadConfigValues();
        Bukkit.getPluginManager().registerEvents(viewDistance, plugin);

        antiFreeCam.loadConfigValues();
        antiFreeCam.start();
        entityOcclusion.loadConfigValues();
        entityOcclusion.start();

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            blockObfuscation = new BlockObfuscationManager(plugin);
            blockObfuscation.loadConfigValues();
            blockObfuscation.start();
        } else {
            plugin.getLogger().warning("ProtocolLib not found - optional block obfuscation is disabled. "
                + "Player-visibility, view-distance capping and free-cam detection still work.");
        }
        paperAntiXraySupport.checkAsync();
        enabled = true;
    }

    @Override
    public void onDisable() {
        playerVisibility.stop();
        org.bukkit.event.HandlerList.unregisterAll(viewDistance);
        antiFreeCam.stop();
        if (blockObfuscation != null) {
            blockObfuscation.stop();
            blockObfuscation = null;
        }
        entityOcclusion.stop();
        enabled = false;
    }
}
