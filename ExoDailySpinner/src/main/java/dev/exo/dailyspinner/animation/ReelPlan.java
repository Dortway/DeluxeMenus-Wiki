package dev.exo.dailyspinner.animation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Precomputed reel animation. The strip is built so that after the final step the predetermined
 * winner sits exactly at the centre index. The plan is purely cosmetic: the winner is decided
 * and persisted before a plan is ever created.
 */
public final class ReelPlan<T> {

    private final List<T> strip;
    private final int[] delays;
    private final int reelLength;
    private final int centerIndex;

    private ReelPlan(List<T> strip, int[] delays, int reelLength, int centerIndex) {
        this.strip = strip;
        this.delays = delays;
        this.reelLength = reelLength;
        this.centerIndex = centerIndex;
    }

    /**
     * @param reelLength  number of visible reel slots
     * @param centerIndex index (within the visible reel) of the winning position
     * @param steps       number of single-slot shifts before landing
     * @param minDelay    ticks between shifts at full speed (>= 1)
     * @param maxDelay    ticks between the final shifts (>= minDelay)
     * @param easing      easing exponent; larger values slow down later and more sharply
     */
    public static <T> ReelPlan<T> create(int reelLength, int centerIndex, int steps, T winner,
                                         Supplier<T> filler, int minDelay, int maxDelay, double easing) {
        if (reelLength < 1) {
            throw new IllegalArgumentException("reelLength must be >= 1");
        }
        if (centerIndex < 0 || centerIndex >= reelLength) {
            throw new IllegalArgumentException("centerIndex out of range");
        }
        if (steps < 1) {
            throw new IllegalArgumentException("steps must be >= 1");
        }
        if (minDelay < 1 || maxDelay < minDelay) {
            throw new IllegalArgumentException("invalid delays");
        }
        if (!Double.isFinite(easing) || easing <= 0) {
            throw new IllegalArgumentException("easing must be positive");
        }
        int size = steps + reelLength;
        List<T> strip = new ArrayList<>(size);
        int winnerIndex = steps + centerIndex;
        for (int i = 0; i < size; i++) {
            strip.add(i == winnerIndex ? winner : filler.get());
        }
        int[] delays = new int[steps];
        for (int s = 0; s < steps; s++) {
            double t = steps == 1 ? 1.0 : (double) s / (steps - 1);
            double eased = Math.pow(t, easing);
            delays[s] = Math.max(1, (int) Math.round(minDelay + (maxDelay - minDelay) * eased));
        }
        return new ReelPlan<>(Collections.unmodifiableList(strip), delays, reelLength, centerIndex);
    }

    public int steps() {
        return delays.length;
    }

    /** Ticks to wait before performing shift number {@code step} (0-based). */
    public int delayBefore(int step) {
        return delays[step];
    }

    public int totalTicks() {
        int sum = 0;
        for (int d : delays) {
            sum += d;
        }
        return sum;
    }

    /** Visible reel contents after {@code shifts} shifts (0 = initial state). */
    public List<T> window(int shifts) {
        if (shifts < 0 || shifts > delays.length) {
            throw new IllegalArgumentException("shift out of range");
        }
        return strip.subList(shifts, shifts + reelLength);
    }

    public T centerAt(int shifts) {
        return window(shifts).get(centerIndex);
    }

    public T finalCenter() {
        return centerAt(delays.length);
    }

    public int reelLength() {
        return reelLength;
    }
}
