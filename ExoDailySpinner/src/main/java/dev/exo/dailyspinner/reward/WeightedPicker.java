package dev.exo.dailyspinner.reward;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Immutable weighted selector. Weights are relative: an entry's probability is its weight divided
 * by the sum of all weights. Selection is O(log n) using a cumulative table built once.
 */
public final class WeightedPicker<T> {

    /** Upper bound for a single weight; keeps sums finite and precise. */
    public static final double MAX_WEIGHT = 1_000_000_000d;

    private final List<T> entries;
    private final double[] cumulative;
    private final double[] weights;
    private final double total;

    public WeightedPicker(List<T> entries, double[] weights) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(weights, "weights");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("at least one entry is required");
        }
        if (entries.size() != weights.length) {
            throw new IllegalArgumentException("entries and weights differ in size");
        }
        this.entries = List.copyOf(entries);
        this.weights = weights.clone();
        this.cumulative = new double[weights.length];
        double sum = 0;
        for (int i = 0; i < weights.length; i++) {
            validateWeight(weights[i]);
            sum += weights[i];
            cumulative[i] = sum;
        }
        if (!Double.isFinite(sum) || sum <= 0) {
            throw new IllegalArgumentException("total weight must be finite and positive");
        }
        this.total = sum;
    }

    public static void validateWeight(double weight) {
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be a finite number");
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be greater than 0");
        }
        if (weight > MAX_WEIGHT) {
            throw new IllegalArgumentException("weight must not exceed " + (long) MAX_WEIGHT);
        }
    }

    public T pick(RandomGenerator random) {
        double roll = random.nextDouble() * total;
        int index = Arrays.binarySearch(cumulative, roll);
        // binarySearch returns (-(insertion point) - 1) when not found; an exact hit belongs to the next bucket.
        index = index >= 0 ? index + 1 : -index - 1;
        if (index >= entries.size()) {
            index = entries.size() - 1;
        }
        return entries.get(index);
    }

    public double probability(int index) {
        return weights[index] / total;
    }

    public double totalWeight() {
        return total;
    }

    public int size() {
        return entries.size();
    }

    public List<T> entries() {
        return entries;
    }
}
