package com.legendary.plugin.util;

/**
 * Basic descriptive statistics used by the rotation-uniformity check
 * (bots/aimbots tend to produce unnaturally low variance in yaw deltas).
 */
public final class StatsUtil {

    private StatsUtil() {}

    public record Result(double mean, double coefficientOfVariation) {}

    public static Result coefficientOfVariation(double[] values) {
        if (values.length == 0) return new Result(0, 0);
        double sum = 0;
        for (double v : values) sum += v;
        double mean = sum / values.length;
        if (mean == 0) return new Result(0, 0);
        double sqSum = 0;
        for (double v : values) sqSum += (v - mean) * (v - mean);
        double stdDev = Math.sqrt(sqSum / values.length);
        return new Result(mean, stdDev / mean);
    }
}
