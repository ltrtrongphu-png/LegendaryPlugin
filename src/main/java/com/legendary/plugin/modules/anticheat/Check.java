package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

/**
 * Base class for every anti-cheat detection ("check"). Centralizes the
 * enable flag, config-path prefix and the flag()/ban() plumbing so each
 * concrete check only has to implement its detection logic + config
 * loading - replacing the duplicated Toggleable/ConfigReloadable pattern
 * copy-pasted across all 46 check classes.
 */
public abstract class Check implements Listener {

    public enum Confidence {
        HIGH, MEDIUM, LOW
    }

    protected final LegendaryPlugin plugin;
    protected final AnticheatModule anticheat;
    protected final String id;
    protected final String displayName;
    protected volatile boolean enabled;
    private volatile boolean exemptBedrock;

    protected Check(AnticheatModule anticheat, String id, String displayName) {
        this.anticheat = anticheat;
        this.plugin = anticheat.getPlugin();
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) {
        this.enabled = value;
        this.exemptBedrock = plugin.getConfig().getBoolean(path("exempt-bedrock"), false);
    }

    /** Re-reads this check's config.yml section (path: anticheat.checks.<id>.*). */
    public abstract void loadConfigValues();

    protected String path(String key) {
        return "anticheat.checks." + id + "." + key;
    }

    protected boolean shouldSkip(Player player) {
        long startedAt = System.nanoTime();
        try {
            if (!enabled || anticheat.getPlayerStateTracker().shouldSkip(player)) return true;
            return exemptBedrock && com.legendary.plugin.util.GeyserExemption.isBedrock(player);
        } finally {
            anticheat.getPerfProfiler().record(id, System.nanoTime() - startedAt);
        }
    }

    /** Registers one violation and runs alert/escalation/replay side effects. */
    protected void flag(Player player, double weight, String detail) {
        anticheat.flag(this, player, weight, detail);
    }

    public static Confidence confidenceFor(String checkId) {
        return switch (checkId.toLowerCase(java.util.Locale.ROOT)) {
            case "invalidpacket", "impossiblehit", "badpackets" -> Confidence.HIGH;
            case "behaviorprofile", "mccanalyze", "packetfingerprint",
                 "combatheuristics", "movementheuristics", "xray", "clickdistribution",
                 "smoothaura", "reachprofiling" -> Confidence.LOW;
            default -> Confidence.MEDIUM;
        };
    }
}
