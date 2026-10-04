package com.exoblacksmith.effect;

import java.util.List;

/**
 * Combines damage reductions. Values are fractions in [0, 1). The result is always clamped so the
 * total reduction never exceeds {@code maxTotal} (itself capped at 0.95 by config validation), which
 * means combined effects can never produce immunity or healing.
 */
public final class ReductionMath {
    private ReductionMath() {
    }

    /** Returns the damage multiplier in [1 - maxTotal, 1]. */
    public static double multiplier(List<Double> reductions, boolean multiplicative, double maxTotal) {
        double total;
        if (multiplicative) {
            double remaining = 1.0;
            for (double r : reductions) {
                remaining *= 1.0 - clamp01(r);
            }
            total = 1.0 - remaining;
        } else {
            total = 0;
            for (double r : reductions) {
                total += clamp01(r);
            }
        }
        total = Math.min(Math.max(total, 0), Math.min(maxTotal, 0.95));
        return 1.0 - total;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(0.95, v));
    }

    /** Applies a capped additive bonus; never negative. */
    public static double withBonus(double damage, double bonus, double maxBonus) {
        return Math.max(0, damage + Math.max(0, Math.min(bonus, maxBonus)));
    }
}
