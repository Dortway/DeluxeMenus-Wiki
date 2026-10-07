package dev.exoquests.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exoquests.core.time.DurationFormat;
import dev.exoquests.core.time.ResetSchedule;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ResetScheduleTest {

    private final ResetSchedule london = ResetSchedule.parse("00:00", "Europe/London");

    @Test
    void periodChangesExactlyAtLondonMidnightInSummer() {
        // 2026-07-01 00:00 BST = 2026-06-30T23:00Z
        Instant boundary = Instant.parse("2026-06-30T23:00:00Z");
        assertEquals("2026-06-30", london.periodKey(boundary.minusMillis(1)));
        assertEquals("2026-07-01", london.periodKey(boundary));
        assertEquals(boundary, london.nextReset(boundary.minusMillis(1)));
        assertEquals(boundary.plus(Duration.ofDays(1)), london.nextReset(boundary));
    }

    @Test
    void periodChangesExactlyAtLondonMidnightInWinter() {
        Instant boundary = Instant.parse("2026-12-01T00:00:00Z");
        assertEquals("2026-11-30", london.periodKey(boundary.minusNanos(1)));
        assertEquals("2026-12-01", london.periodKey(boundary));
    }

    @Test
    void springForwardDayIsTwentyThreeHoursAndGapTimeIsShiftedForward() {
        // UK clocks go 01:00 GMT -> 02:00 BST on 2026-03-29. 01:30 does not exist that day.
        ResetSchedule s = ResetSchedule.parse("01:30", "Europe/London");
        Instant onDay = s.boundary(LocalDate.parse("2026-03-29"));
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), onDay); // 02:30 BST
        Instant dayBefore = s.boundary(LocalDate.parse("2026-03-28"));
        assertEquals(Instant.parse("2026-03-28T01:30:00Z"), dayBefore); // 01:30 GMT
        assertEquals("2026-03-28", s.periodKey(onDay.minusSeconds(1)));
        assertEquals("2026-03-29", s.periodKey(onDay));
        Instant dayAfter = s.boundary(LocalDate.parse("2026-03-30"));
        assertEquals(Duration.ofHours(23), Duration.between(onDay, dayAfter));
    }

    @Test
    void autumnOverlapUsesEarlierOffsetSoResetHappensOnce() {
        // UK clocks go 02:00 BST -> 01:00 GMT on 2026-10-25; 01:30 happens twice.
        ResetSchedule s = ResetSchedule.parse("01:30", "Europe/London");
        Instant b = s.boundary(LocalDate.parse("2026-10-25"));
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), b); // first 01:30 (BST)
        // The second 01:30 (GMT) one hour later is still the same period.
        assertEquals("2026-10-25", s.periodKey(Instant.parse("2026-10-25T01:30:00Z")));
        assertEquals(Duration.ofHours(25), Duration.between(b, s.boundary(LocalDate.parse("2026-10-26"))));
    }

    @Test
    void midnightResetAcrossDstChangesHasCorrectLengths() {
        Instant m29 = london.boundary(LocalDate.parse("2026-03-29"));
        Instant m30 = london.boundary(LocalDate.parse("2026-03-30"));
        assertEquals(Duration.ofHours(23), Duration.between(m29, m30));
        Instant o25 = london.boundary(LocalDate.parse("2026-10-25"));
        Instant o26 = london.boundary(LocalDate.parse("2026-10-26"));
        assertEquals(Duration.ofHours(25), Duration.between(o25, o26));
    }

    @Test
    void periodsAreMonotonicAcrossAWholeYear() {
        ResetSchedule s = ResetSchedule.parse("01:30", "Europe/London");
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        LocalDate previous = s.periodAt(t);
        int changes = 0;
        for (int i = 0; i < 365 * 24 * 4; i++) {
            t = t.plus(Duration.ofMinutes(15));
            LocalDate p = s.periodAt(t);
            assertTrue(!p.isBefore(previous), "period went backwards at " + t);
            if (!p.equals(previous)) {
                assertEquals(previous.plusDays(1), p, "skipped a period at " + t);
                changes++;
            }
            previous = p;
            assertTrue(s.nextReset(t).isAfter(t));
        }
        assertEquals(365, changes);
    }

    @Test
    void otherTimezonesAndTimes() {
        ResetSchedule ny = ResetSchedule.parse("06:15", "America/New_York");
        assertEquals("2026-01-14", ny.periodKey(Instant.parse("2026-01-15T11:14:59Z")));
        assertEquals("2026-01-15", ny.periodKey(Instant.parse("2026-01-15T11:15:00Z")));
    }

    @Test
    void invalidScheduleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ResetSchedule.parse("25:00", "Europe/London"));
        assertThrows(IllegalArgumentException.class, () -> ResetSchedule.parse("00:00", "Mars/Olympus"));
    }

    @Test
    void durationFormatting() {
        assertEquals("5h 23m", DurationFormat.compact(Duration.ofMinutes(5 * 60 + 23)));
        assertEquals("42s", DurationFormat.compact(Duration.ofSeconds(42)));
        assertEquals("3m 5s", DurationFormat.compact(Duration.ofSeconds(185)));
        assertEquals("0s", DurationFormat.compact(Duration.ofSeconds(-5)));
    }
}
