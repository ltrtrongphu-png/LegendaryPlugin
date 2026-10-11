package com.legendary.plugin.modules.optimization;

/**
 * Linearly ramps a joining player's view distance from a low start value
 * up to the server target over a short window, smoothing the chunk-load
 * spike that happens right after login. Ported from
 * SmartOptimizer.JoinRampCalculator.
 */
public final class JoinRampCalculator {

    private final int startDistance;
    private final int targetDistance;
    private final long rampDurationMillis;

    public JoinRampCalculator(int startDistance, int targetDistance, long rampDurationMillis) {
        this.startDistance = startDistance;
        this.targetDistance = targetDistance;
        this.rampDurationMillis = Math.max(1, rampDurationMillis);
    }

    public int distanceAt(long elapsedMillis) {
        if (elapsedMillis >= rampDurationMillis) return targetDistance;
        double progress = elapsedMillis / (double) rampDurationMillis;
        return (int) Math.round(startDistance + (targetDistance - startDistance) * progress);
    }

    public boolean isComplete(long elapsedMillis) {
        return elapsedMillis >= rampDurationMillis;
    }

    public int getTargetDistance() {
        return targetDistance;
    }
}
