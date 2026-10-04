package com.exoblacksmith.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReductionMathTest {
    @Test
    void multiplicativeCombination() {
        assertEquals(1 - 0.85 * 0.9, 1 - ReductionMath.multiplier(List.of(0.15, 0.10), true, 0.8), 1e-9);
    }

    @Test
    void additiveCombination() {
        assertEquals(0.25, 1 - ReductionMath.multiplier(List.of(0.15, 0.10), false, 0.8), 1e-9);
    }

    @Test
    void neverImmunityOrHealing() {
        double m = ReductionMath.multiplier(List.of(0.95, 0.95, 0.95, 0.95), false, 0.8);
        assertEquals(0.2, m, 1e-9);
        assertTrue(ReductionMath.multiplier(List.of(5.0), true, 5.0) >= 0.05, "config cap is clamped to 95%");
        assertTrue(ReductionMath.multiplier(List.of(-3.0), true, 0.8) <= 1.0, "negative reductions cannot add damage");
        assertEquals(1.0, ReductionMath.multiplier(List.of(), true, 0.8));
    }

    @Test
    void bonusIsCappedAndNonNegative() {
        assertEquals(10.0, ReductionMath.withBonus(4, 100, 6), 1e-9);
        assertEquals(4.0, ReductionMath.withBonus(4, -5, 6), 1e-9);
    }
}
