package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;

/**
 * Decides the current server "stress level" from TPS and derives the
 * concrete tuning {@link Settings} every optimization sub-module reads.
 *
 * PERFORMANCE UPGRADE: adds entity-ai-skip settings to the Settings
 * record, and more aggressive resource reduction at higher lag levels
 * (deeper view/sim distance cuts, lower mob multipliers).
 */
public final class OptimizationEngine {

    public enum Level {
        NORMAL, MILD, MODERATE, SEVERE;

        @Override
        public String toString() {
            return switch (this) {
                case NORMAL -> "NORMAL";
                case MILD -> "MILD";
                case MODERATE -> "MODERATE";
                case SEVERE -> "SEVERE";
            };
        }
    }

    public static final class Settings {
        public final Level level;
        public final int viewDistance;
        public final int simulationDistance;
        public final double mobSpawnMultiplier;
        public final double itemMergeRadius;
        public final double hopperThrottleChance;
        public final int entityAiSkipRadius;

        public Settings(Level level, int viewDistance, int simulationDistance,
                         double mobSpawnMultiplier, double itemMergeRadius, double hopperThrottleChance,
                         int entityAiSkipRadius) {
            this.level = level;
            this.viewDistance = viewDistance;
            this.simulationDistance = simulationDistance;
            this.mobSpawnMultiplier = mobSpawnMultiplier;
            this.itemMergeRadius = itemMergeRadius;
            this.hopperThrottleChance = hopperThrottleChance;
            this.entityAiSkipRadius = entityAiSkipRadius;
        }

        @Override
        public String toString() {
            return level + " (view=" + viewDistance + ", sim=" + simulationDistance
                + ", mobMult=" + mobSpawnMultiplier + ", mergeR=" + itemMergeRadius
                + ", hopperThrottle=" + hopperThrottleChance
                + ", aiSkip=" + entityAiSkipRadius + ")";
        }
    }

    private double mildThreshold = 19.3;
    private double moderateThreshold = 17.0;
    private double severeThreshold = 14.0;
    private int baseViewDistance = 10;
    private int baseSimulationDistance = 10;

    private static final int MIN_DISTANCE = 2;
    private static final int MAX_DISTANCE = 32;

    public void configure(double mild, double moderate, double severe, int baseView, int baseSim) {
        this.mildThreshold = mild;
        this.moderateThreshold = moderate;
        this.severeThreshold = severe;
        this.baseViewDistance = clamp(baseView);
        this.baseSimulationDistance = clamp(baseSim);
    }

    private static int clamp(int distance) {
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
    }

    public Level levelFor(double tps) {
        if (tps <= severeThreshold) return Level.SEVERE;
        if (tps <= moderateThreshold) return Level.MODERATE;
        if (tps <= mildThreshold) return Level.MILD;
        return Level.NORMAL;
    }

    public Settings settingsFor(Level level) {
        return switch (level) {
            case NORMAL -> new Settings(level, clamp(baseViewDistance), clamp(baseSimulationDistance),
                1.0, 0.0, 0.0, 0);
            case MILD -> new Settings(level, clamp(baseViewDistance - 1), clamp(baseSimulationDistance),
                0.80, 4.0, 0.10, 32);
            case MODERATE -> new Settings(level, clamp(baseViewDistance - 2), clamp(baseSimulationDistance - 1),
                0.50, 6.0, 0.35, 16);
            case SEVERE -> new Settings(level, clamp(baseViewDistance - 4), clamp(baseSimulationDistance - 2),
                0.25, 8.0, 0.65, 8);
        };
    }
}
