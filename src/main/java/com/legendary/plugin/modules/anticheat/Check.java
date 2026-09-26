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

    protected final LegendaryPlugin plugin;
    protected final AnticheatModule anticheat;
    protected final String id;
    protected final String displayName;
    protected volatile boolean enabled;

    protected Check(AnticheatModule anticheat, String id, String displayName) {
        this.anticheat = anticheat;
        this.plugin = anticheat.getPlugin();
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public boolean isEnabled() { return enabled; }

    /** Re-reads this check's config.yml section (path: anticheat.checks.<id>.*). */
    public abstract void loadConfigValues();

    protected String path(String key) {
        return "anticheat.checks." + id + "." + key;
    }

    protected boolean shouldSkip(Player player) {
        if (!enabled || player.isDead()) return true;
        if (anticheat.getBypassGuard().shouldBypass(player, id)) return true;
        if (plugin.getConfig().getBoolean(path("exempt-bedrock"), false)
            && com.legendary.plugin.util.GeyserExemption.isBedrock(player)) {
            return true;
        }
        return false;
    }

    /** Registers one violation and runs alert/escalation/replay side effects. */
    protected void flag(Player player, double weight, String detail) {
        anticheat.flag(this, player, weight, detail);
    }
}
