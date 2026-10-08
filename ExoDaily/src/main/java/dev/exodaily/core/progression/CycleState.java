package dev.exodaily.core.progression;

import java.time.LocalDate;

/**
 * Where a player is on a given calendar date.
 *
 * @param cycleNumber the cycle identifier
 * @param cycleStart  date of day 1
 * @param cycleLength days in this cycle
 * @param day         1-based day within the cycle
 * @param date        the calendar date this state was resolved for
 */
public record CycleState(int cycleNumber, LocalDate cycleStart, int cycleLength, int day, LocalDate date) {

    public LocalDate dateOfDay(int dayNumber) {
        return cycleStart.plusDays(dayNumber - 1L);
    }

    public LocalDate cycleEnd() {
        return cycleStart.plusDays(cycleLength - 1L);
    }

    public int daysRemaining() {
        return cycleLength - day;
    }
}
