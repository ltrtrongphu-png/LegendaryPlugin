package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Check-profile presets that let admins switch all 46 checks between
 * strict, balanced, and lenient configurations with a single config
 * key instead of tuning each check individually.
 *
 * Each preset defines a global weight multiplier and a per-check
 * enable override map. The multiplier scales every check's flag weight
 * uniformly; the enable map lets a preset turn specific checks on or off.
 *
 * Active preset is selected via {@code anticheat.profile} in config.yml.
 * Per-check config values always take precedence over preset overrides.
 */
public final class CheckProfile {

    public enum Preset {
        LENIENT("lenient", 0.6, Map.of(
            "timer", false,
            "rotation", false,
            "blink", false,
            "guimove", false
        )),
        BALANCED("balanced", 1.0, Map.of()),
        STRICT("strict", 1.4, Map.of(
            "noswing", true,
            "fastinteract", true,
            "bowspam", true,
            "chestaura", true
        ));

        private final String configName;
        private final double weightMultiplier;
        private final Map<String, Boolean> checkOverrides;

        Preset(String configName, double weightMultiplier, Map<String, Boolean> checkOverrides) {
            this.configName = configName;
            this.weightMultiplier = weightMultiplier;
            this.checkOverrides = checkOverrides;
        }

        public String getConfigName() { return configName; }
        public double getWeightMultiplier() { return weightMultiplier; }

        /** Returns whether a check should be enabled under this preset, or null if no override. */
        public Boolean shouldEnable(String checkId) { return checkOverrides.get(checkId); }
    }

    private final LegendaryPlugin plugin;
    private Preset activePreset = Preset.BALANCED;

    public CheckProfile(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        String name = plugin.getConfig().getString("anticheat.profile", "balanced");
        activePreset = switch (name.toLowerCase()) {
            case "lenient" -> Preset.LENIENT;
            case "strict" -> Preset.STRICT;
            default -> Preset.BALANCED;
        };
    }

    public Preset getActivePreset() { return activePreset; }

    /** Returns the weight multiplier from the active preset (stacks with trust multiplier). */
    public double getWeightMultiplier() { return activePreset.getWeightMultiplier(); }

    /**
     * Returns whether a check should be enabled under the active preset.
     * Returns null if the preset has no opinion (use per-check config).
     */
    public Boolean shouldEnable(String checkId) { return activePreset.shouldEnable(checkId); }

    public Map<String, Boolean> getOverrides() { return activePreset.checkOverrides; }

    public static Preset fromConfig(String name) {
        return switch (name == null ? "balanced" : name.toLowerCase()) {
            case "lenient" -> Preset.LENIENT;
            case "strict" -> Preset.STRICT;
            default -> Preset.BALANCED;
        };
    }
}
