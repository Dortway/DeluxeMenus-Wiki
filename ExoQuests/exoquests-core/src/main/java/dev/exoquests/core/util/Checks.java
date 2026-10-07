package dev.exoquests.core.util;

/** Small arithmetic helpers that refuse silent overflow. */
public final class Checks {

    private Checks() {
    }

    /** Adds two non-negative values, saturating at {@code cap}. */
    public static long addCapped(long a, long b, long cap) {
        if (a < 0 || b < 0) {
            throw new IllegalArgumentException("negative operand");
        }
        long sum = a + b;
        if (sum < 0 || sum > cap) {
            return cap;
        }
        return sum;
    }
}
