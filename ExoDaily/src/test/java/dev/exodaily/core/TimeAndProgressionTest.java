package dev.exodaily.core;

import dev.exodaily.core.progression.CycleState;
import dev.exodaily.core.progression.PlayerProfile;
import dev.exodaily.core.progression.Progression;
import dev.exodaily.core.time.DailyClock;
import dev.exodaily.support.MutableClock;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeAndProgressionTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static Instant london(String dateTime) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(dateTime), LONDON).toInstant();
    }

    @Nested
    class MidnightAndDaylightSaving {

        @Test
        void dayChangesExactlyAtLocalMidnight() {
            MutableClock source = new MutableClock(london("2026-07-14T23:59:59"));
            DailyClock clock = new DailyClock(source, LONDON);
            assertEquals(LocalDate.of(2026, 7, 14), clock.today());
            source.advance(Duration.ofSeconds(1));
            assertEquals(LocalDate.of(2026, 7, 15), clock.today());
        }

        @Test
        void midnightUsesConfiguredZoneNotUtc() {
            // 23:30 UTC on 14 July is 00:30 on 15 July in London (BST, UTC+1).
            MutableClock source = new MutableClock(Instant.parse("2026-07-14T23:30:00Z"));
            DailyClock clock = new DailyClock(source, LONDON);
            assertEquals(LocalDate.of(2026, 7, 15), clock.today());
            clock.zone(ZoneId.of("UTC"));
            assertEquals(LocalDate.of(2026, 7, 14), clock.today());
        }

        @Test
        void springForwardDayLasts23Hours() {
            // Clocks go forward at 01:00 on 29 March 2026 in London.
            MutableClock source = new MutableClock(london("2026-03-29T00:00:00"));
            DailyClock clock = new DailyClock(source, LONDON);
            assertEquals(Duration.ofHours(23), clock.untilReset());
            source.set(london("2026-03-29T12:00:00"));
            // After the shift, noon to midnight is a normal 12 hours.
            assertEquals(Duration.ofHours(12), clock.untilReset());
        }

        @Test
        void fallBackDayLasts25Hours() {
            // Clocks go back at 02:00 on 25 October 2026 in London.
            MutableClock source = new MutableClock(london("2026-10-25T00:00:00"));
            DailyClock clock = new DailyClock(source, LONDON);
            assertEquals(Duration.ofHours(25), clock.untilReset());
            // 24 real hours after midnight it is still 25 October locally (23:00).
            source.advance(Duration.ofHours(24));
            assertEquals(LocalDate.of(2026, 10, 25), clock.today());
            source.advance(Duration.ofHours(1));
            assertEquals(LocalDate.of(2026, 10, 26), clock.today());
        }

        @Test
        void missingMidnightResolvesToFirstValidInstant() {
            // Sao Paulo skipped 00:00-01:00 on 4 November 2018: the day began at 01:00.
            ZoneId saoPaulo = ZoneId.of("America/Sao_Paulo");
            ZonedDateTime evening = ZonedDateTime.of(LocalDate.of(2018, 11, 3), LocalTime.of(23, 0), saoPaulo);
            ZonedDateTime reset = DailyClock.nextReset(evening);
            assertEquals(LocalDate.of(2018, 11, 4), reset.toLocalDate());
            assertEquals(LocalTime.of(1, 0), reset.toLocalTime());
            assertEquals(Duration.ofHours(1), Duration.between(evening, reset));
        }

        @Test
        void progressionAcrossDstCountsCalendarDaysNotHours() {
            PlayerProfile profile = Progression.startNew(PLAYER, LocalDate.of(2026, 3, 28), 30);
            // The next two calendar days include the 23-hour day.
            assertEquals(2, Progression.resolve(profile, LocalDate.of(2026, 3, 29), 30).state().day());
            assertEquals(3, Progression.resolve(profile, LocalDate.of(2026, 3, 30), 30).state().day());
            PlayerProfile autumn = Progression.startNew(PLAYER, LocalDate.of(2026, 10, 24), 30);
            assertEquals(2, Progression.resolve(autumn, LocalDate.of(2026, 10, 25), 30).state().day());
            assertEquals(3, Progression.resolve(autumn, LocalDate.of(2026, 10, 26), 30).state().day());
        }
    }

    @Nested
    class CycleProgression {

        private final LocalDate start = LocalDate.of(2026, 1, 1);

        @Test
        void dayOneIsAvailableImmediately() {
            PlayerProfile profile = Progression.startNew(PLAYER, start, 30);
            Progression.Resolution resolution = Progression.resolve(profile, start, 30);
            assertEquals(1, resolution.state().day());
            assertEquals(1, resolution.state().cycleNumber());
            assertFalse(resolution.changed());
        }

        @Test
        void offlineDaysStillAdvanceAndAreSkipped() {
            PlayerProfile profile = Progression.startNew(PLAYER, start, 30);
            // Away from 2 January to 9 January: on return it is day 10, days 2-9 were missed.
            CycleState state = Progression.resolve(profile, LocalDate.of(2026, 1, 10), 30).state();
            assertEquals(10, state.day());
            assertEquals(LocalDate.of(2026, 1, 10), state.dateOfDay(10));
        }

        @Test
        void dayThirtyRollsIntoNewCycle() {
            PlayerProfile profile = Progression.startNew(PLAYER, start, 30);
            assertEquals(30, Progression.resolve(profile, LocalDate.of(2026, 1, 30), 30).state().day());
            Progression.Resolution next = Progression.resolve(profile, LocalDate.of(2026, 1, 31), 30);
            assertTrue(next.changed());
            assertEquals(2, next.state().cycleNumber());
            assertEquals(1, next.state().day());
            assertEquals(LocalDate.of(2026, 1, 31), next.profile().cycleStart());
        }

        @Test
        void longAbsenceSkipsWholeCycles() {
            PlayerProfile profile = Progression.startNew(PLAYER, start, 30);
            // 65 days later: cycles 1 and 2 are over, day 6 of cycle 3.
            CycleState state = Progression.resolve(profile, start.plusDays(65), 30).state();
            assertEquals(3, state.cycleNumber());
            assertEquals(6, state.day());
        }

        @Test
        void changedCycleLengthOnlyAppliesToTheNextCycle() {
            PlayerProfile profile = Progression.startNew(PLAYER, start, 30);
            // Configured length changed to 7 while on day 10: the running cycle keeps 30 days.
            CycleState running = Progression.resolve(profile, start.plusDays(9), 7).state();
            assertEquals(10, running.day());
            assertEquals(30, running.cycleLength());
            CycleState next = Progression.resolve(profile, start.plusDays(30), 7).state();
            assertEquals(2, next.cycleNumber());
            assertEquals(7, next.cycleLength());
            CycleState after = Progression.resolve(profile, start.plusDays(37), 7).state();
            assertEquals(3, after.cycleNumber());
            assertEquals(1, after.day());
        }

        @Test
        void dateBeforeCycleStartStaysOnDayOne() {
            PlayerProfile profile = Progression.startNew(PLAYER, start, 30);
            CycleState state = Progression.resolve(profile, start.minusDays(1), 30).state();
            assertEquals(1, state.day());
            assertEquals(1, state.cycleNumber());
        }

        @Test
        void setDayKeepsCycleAndMovesStart() {
            PlayerProfile profile = Progression.startNew(PLAYER, start, 30);
            LocalDate today = start.plusDays(3);
            PlayerProfile moved = Progression.setDay(profile, today, 20, 30);
            assertEquals(1, moved.cycleNumber());
            assertEquals(20, Progression.resolve(moved, today, 30).state().day());
            assertThrows(IllegalArgumentException.class, () -> Progression.setDay(profile, today, 31, 30));
            assertThrows(IllegalArgumentException.class, () -> Progression.setDay(profile, today, 0, 30));
        }

        @Test
        void resetStartsANewCycleToday() {
            PlayerProfile profile = Progression.startNew(PLAYER, start, 30);
            LocalDate today = start.plusDays(40); // already in cycle 2
            PlayerProfile reset = Progression.reset(profile, today, 30);
            assertEquals(3, reset.cycleNumber());
            assertEquals(today, reset.cycleStart());
            assertEquals(1, Progression.resolve(reset, today, 30).state().day());
        }
    }
}
