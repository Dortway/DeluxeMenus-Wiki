package dev.exo.dailyspinner.reward;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class WeightedPickerTest {

    @Test
    void probabilitiesAreRelativeWeights() {
        WeightedPicker<String> picker = new WeightedPicker<>(List.of("a", "b", "c"), new double[]{1, 3, 6});
        assertEquals(10.0, picker.totalWeight());
        assertEquals(0.1, picker.probability(0), 1e-12);
        assertEquals(0.3, picker.probability(1), 1e-12);
        assertEquals(0.6, picker.probability(2), 1e-12);
    }

    @Test
    void distributionMatchesWeights() {
        WeightedPicker<String> picker = new WeightedPicker<>(List.of("common", "rare", "legendary"), new double[]{70, 25, 5});
        RandomGenerator random = new SplittableRandom(42);
        Map<String, Integer> counts = new HashMap<>();
        int samples = 200_000;
        for (int i = 0; i < samples; i++) {
            counts.merge(picker.pick(random), 1, Integer::sum);
        }
        assertEquals(0.70, counts.get("common") / (double) samples, 0.01);
        assertEquals(0.25, counts.get("rare") / (double) samples, 0.01);
        assertEquals(0.05, counts.get("legendary") / (double) samples, 0.005);
    }

    @Test
    void boundaryRollsMapToCorrectBucket() {
        WeightedPicker<String> picker = new WeightedPicker<>(List.of("a", "b"), new double[]{1, 1});
        assertEquals("a", picker.pick(fixed(0.0)));
        assertEquals("b", picker.pick(fixed(0.5))); // exact boundary belongs to the next bucket
        assertEquals("b", picker.pick(fixed(Math.nextDown(1.0))));
    }

    @Test
    void singleEntryAlwaysWins() {
        WeightedPicker<String> picker = new WeightedPicker<>(List.of("only"), new double[]{0.001});
        assertEquals("only", picker.pick(new SplittableRandom(1)));
        assertEquals(1.0, picker.probability(0));
    }

    @Test
    void rejectsInvalidWeights() {
        assertThrows(IllegalArgumentException.class, () -> new WeightedPicker<>(List.of("a"), new double[]{0}));
        assertThrows(IllegalArgumentException.class, () -> new WeightedPicker<>(List.of("a"), new double[]{-1}));
        assertThrows(IllegalArgumentException.class, () -> new WeightedPicker<>(List.of("a"), new double[]{Double.NaN}));
        assertThrows(IllegalArgumentException.class, () -> new WeightedPicker<>(List.of("a"), new double[]{Double.POSITIVE_INFINITY}));
        assertThrows(IllegalArgumentException.class, () -> new WeightedPicker<>(List.of("a"), new double[]{2e9}));
        assertThrows(IllegalArgumentException.class, () -> new WeightedPicker<>(List.of(), new double[]{}));
        assertThrows(IllegalArgumentException.class, () -> new WeightedPicker<>(List.of("a", "b"), new double[]{1}));
    }

    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }
}
