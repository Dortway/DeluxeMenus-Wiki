package dev.exo.dailyspinner.cooldown;

import dev.exo.dailyspinner.util.DurationParser;
import dev.exo.dailyspinner.util.TimeFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CooldownCalculatorTest {

    private static final long DAY = 86_400_000L;

    @Test
    void neverSpunIsReady() {
        assertTrue(CooldownCalculator.isReady(null, DAY, 1000));
        assertEquals(0, CooldownCalculator.remaining(null, DAY, 1000));
    }

    @Test
    void remainingCountsDown() {
        long last = 1_000_000;
        assertEquals(DAY, CooldownCalculator.remaining(last, DAY, last));
        assertEquals(DAY - 5000, CooldownCalculator.remaining(last, DAY, last + 5000));
        assertFalse(CooldownCalculator.isReady(last, DAY, last + DAY - 1));
        assertTrue(CooldownCalculator.isReady(last, DAY, last + DAY));
        assertTrue(CooldownCalculator.isReady(last, DAY, last + DAY * 10));
    }

    @Test
    void clockGoingBackwardsNeverExceedsOneCooldown() {
        long last = 10 * DAY;
        assertEquals(DAY, CooldownCalculator.remaining(last, DAY, last - 3 * DAY));
    }

    @Test
    void parsesDurations() {
        assertEquals(DAY, DurationParser.parseMillis("24h"));
        assertEquals(DAY + 12 * 3_600_000L, DurationParser.parseMillis("1d 12h"));
        assertEquals(90 * 60_000L, DurationParser.parseMillis("90m"));
        assertEquals(3_600_000L, DurationParser.parseMillis("3600"));
        assertEquals(3_723_000L, DurationParser.parseMillis("1h2m3s"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseMillis("abc"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseMillis("12x"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseMillis("h12"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseMillis(""));
    }

    @Test
    void formatsTime() {
        assertEquals("23h 59m", TimeFormat.compact(DAY - 1000));
        assertEquals("1d 0h", TimeFormat.compact(DAY));
        assertEquals("5m 3s", TimeFormat.compact(303_000));
        assertEquals("1s", TimeFormat.compact(1));
        assertEquals("0s", TimeFormat.compact(0));
        assertEquals("<0.01%", TimeFormat.percent(0.00001));
        assertEquals("12.5%", TimeFormat.percent(0.125));
        assertEquals("0.70%", TimeFormat.percent(0.007));
    }
}
