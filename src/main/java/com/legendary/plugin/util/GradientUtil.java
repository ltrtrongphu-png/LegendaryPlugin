package com.legendary.plugin.util;

/**
 * Builds MiniMessage gradient tags, e.g. gradient("Legendary", "#38BDF8", "#A855F7")
 * -> "<gradient:#38BDF8:#A855F7>Legendary</gradient>", so callers can feed the
 * result straight into {@link Text#of(String)}. Replaces the legacy
 * per-character §-code interpolation used by the old AntiESPUltimate /
 * SmartOptimizer GradientUtil classes.
 */
public final class GradientUtil {

    public static final String BRAND_A = "#38BDF8";
    public static final String BRAND_B = "#6366F1";
    public static final String BRAND_C = "#A855F7";
    public static final String OK_A = "#34D399";
    public static final String OK_B = "#10B981";
    public static final String WARN_A = "#FBBF24";
    public static final String WARN_B = "#F59E0B";
    public static final String DANGER_A = "#FB923C";
    public static final String DANGER_B = "#EF4444";

    private GradientUtil() {}

    public static String gradientTag(String text, String... hexStops) {
        return "<gradient:" + String.join(":", hexStops) + ">" + text + "</gradient>";
    }
}
