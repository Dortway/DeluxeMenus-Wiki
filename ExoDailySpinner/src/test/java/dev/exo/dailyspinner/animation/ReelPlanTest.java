package dev.exo.dailyspinner.animation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReelPlanTest {

    @Test
    void winnerLandsExactlyAtCentre() {
        for (int steps = 1; steps <= 80; steps++) {
            AtomicInteger n = new AtomicInteger();
            ReelPlan<String> plan = ReelPlan.create(9, 4, steps, "WIN", () -> "f" + n.incrementAndGet(), 1, 10, 3.0);
            assertEquals("WIN", plan.finalCenter());
            assertEquals(9, plan.window(steps).size());
            assertEquals(1, plan.window(steps).stream().filter("WIN"::equals).count());
        }
    }

    @Test
    void reelSlowsDownMonotonically() {
        ReelPlan<String> plan = ReelPlan.create(9, 4, 40, "W", () -> "x", 1, 12, 3.0);
        for (int s = 1; s < plan.steps(); s++) {
            assertTrue(plan.delayBefore(s) >= plan.delayBefore(s - 1));
        }
        assertEquals(1, plan.delayBefore(0));
        assertEquals(12, plan.delayBefore(plan.steps() - 1));
    }

    @Test
    void windowShiftsByOne() {
        AtomicInteger n = new AtomicInteger();
        ReelPlan<Integer> plan = ReelPlan.create(5, 2, 10, -1, n::incrementAndGet, 1, 2, 1.0);
        assertEquals(plan.window(0).get(1), plan.window(1).get(0));
    }

    @Test
    void rejectsBadParameters() {
        assertThrows(IllegalArgumentException.class, () -> ReelPlan.create(9, 9, 10, "w", () -> "x", 1, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> ReelPlan.create(9, 4, 0, "w", () -> "x", 1, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> ReelPlan.create(9, 4, 10, "w", () -> "x", 3, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> ReelPlan.create(9, 4, 10, "w", () -> "x", 1, 2, Double.NaN));
    }
}
