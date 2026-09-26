package com.legendary.plugin.modules.esp;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * Advisory check (not an enforcer) for Paper's own built-in, server-side
 * Anti-Xray engine (config/paper-world-defaults.yml -> anticheat.anti-xray).
 *
 * Why this exists instead of a hand-rolled full chunk-packet obfuscator:
 * Paper already ships a mature, NMS-level anti-xray implementation that
 * rewrites ore blocks *inside the chunk packet itself* before it is ever
 * serialized, covering every block in a newly-generated/loaded chunk. A
 * ProtocolLib-based packet listener (see {@link BlockObfuscationManager})
 * can only patch individual BLOCK_CHANGE/MULTI_BLOCK_CHANGE packets *after*
 * a chunk is already sent - it cannot rewrite the initial chunk send
 * without re-implementing Paper's paletted-container/chunk-section logic
 * from scratch, which is exactly the kind of fragile, easy-to-get-subtly-
 * wrong NMS surface a plugin should not re-invent. So: Paper's engine is
 * the primary defense for "hide ore in freshly loaded chunks", and
 * {@link BlockObfuscationManager} is a supplementary layer that additionally
 * re-checks blocks that change *after* load (e.g. a piston exposing ore).
 *
 * This class only reads the config (async) and logs actionable guidance;
 * it never edits server files itself, since editing paper-world-defaults.yml
 * requires a full server restart to take effect and doing that silently
 * behind an admin's back would be surprising and out of scope for a plugin.
 */
public final class PaperAntiXraySupport {

    private final LegendaryPlugin plugin;
    private volatile Boolean detectedEnabled = null; // null = unknown/couldn't read
    private volatile int detectedEngineMode = -1;

    public PaperAntiXraySupport(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void checkAsync() {
        if (!plugin.getConfig().getBoolean("esp.block-obfuscation.paper-native-anti-xray-advisory", true)) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            File file = new File(plugin.getServer().getWorldContainer(), "config/paper-world-defaults.yml");
            if (!file.exists()) {
                // Older Paper (pre-1.19 global-config split) keeps it in each world's paper-world.yml instead;
                // that per-world layout is common enough not to warrant a warning on its own.
                return;
            }
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file);
                boolean antiXrayEnabled = yaml.getBoolean("anticheat.anti-xray.enabled", false);
                int engineMode = yaml.getInt("anticheat.anti-xray.engine-mode", 1);
                detectedEnabled = antiXrayEnabled;
                detectedEngineMode = engineMode;
                plugin.getServer().getScheduler().runTask(plugin, () -> report(antiXrayEnabled, engineMode));
            } catch (Exception e) {
                plugin.debug("Could not read paper-world-defaults.yml for anti-xray advisory: " + e.getMessage());
            }
        });
    }

    private void report(boolean enabled, int engineMode) {
        if (!enabled) {
            plugin.getLogger().warning("""
                Paper's built-in Anti-Xray is OFF (config/paper-world-defaults.yml -> anticheat.anti-xray.enabled: false).
                LegendaryPlugin's esp.block-obfuscation only patches block-UPDATE packets - it is a supplement, not a
                replacement, for Paper's own chunk-level ore hiding. Recommended: set anticheat.anti-xray.enabled: true
                and engine-mode: 1 in config/paper-world-defaults.yml, then fully restart (not /reload) the server.
                See https://docs.papermc.io/paper/anti-xray for details.""");
        } else if (engineMode < 2) {
            plugin.getLogger().info("Paper Anti-Xray is enabled (engine-mode " + engineMode
                + "). Consider engine-mode: 2 for stronger protection on high-value servers (higher CPU/bandwidth cost).");
        } else {
            plugin.getLogger().info("Paper Anti-Xray detected: enabled, engine-mode " + engineMode + ". Good.");
        }
    }

    /** Null = unknown (file unreadable / different Paper layout), otherwise Paper's own reported state. */
    public Boolean isNativeAntiXrayEnabled() { return detectedEnabled; }
    public int getDetectedEngineMode() { return detectedEngineMode; }
}
