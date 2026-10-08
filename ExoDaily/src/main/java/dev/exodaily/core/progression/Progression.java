package dev.exodaily.core.progression;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Pure progression rules. All arithmetic works on {@link LocalDate}s so daylight-saving
 * changes cannot shift a day boundary, and offline players progress simply because their
 * persisted cycle start date stays fixed while the calendar moves on.
 */
public final class Progression {

    private Progression() {
    }

    /** Result of resolving a profile against a date. {@code changed} means the profile must be persisted. */
    public record Resolution(PlayerProfile profile, CycleState state, boolean changed) {
    }

    /** A new player's cycle begins on the date of their first {@code /daily}; day 1 is available immediately. */
    public static PlayerProfile startNew(UUID uuid, LocalDate today, int cycleLength) {
        return new PlayerProfile(uuid, today, 1, today, cycleLength);
    }

    /**
     * Resolves the cycle and day for {@code today}. When one or more full cycles have elapsed
     * (including while offline), the profile rolls forward to the cycle containing today.
     * The finished cycle keeps the length it started with; new cycles adopt
     * {@code configuredLength}, so changing the configured length never re-maps a running cycle.
     */
    public static Resolution resolve(PlayerProfile profile, LocalDate today, int configuredLength) {
        if (configuredLength < 1) {
            throw new IllegalArgumentException("configuredLength must be >= 1");
        }
        long elapsed = ChronoUnit.DAYS.between(profile.cycleStart(), today);
        if (elapsed < 0) {
            // The date moved backwards (e.g. the timezone was changed to one further west).
            // Never invent an earlier day: stay on day 1 of the current cycle; claims remain keyed
            // by cycle and day, so nothing can be claimed twice.
            CycleState state = new CycleState(profile.cycleNumber(), profile.cycleStart(), profile.cycleLength(), 1, today);
            return new Resolution(profile, state, false);
        }
        if (elapsed < profile.cycleLength()) {
            CycleState state = new CycleState(profile.cycleNumber(), profile.cycleStart(), profile.cycleLength(),
                    (int) elapsed + 1, today);
            return new Resolution(profile, state, false);
        }
        LocalDate nextStart = profile.cycleStart().plusDays(profile.cycleLength());
        long sinceNext = ChronoUnit.DAYS.between(nextStart, today);
        long extraCycles = sinceNext / configuredLength;
        LocalDate start = nextStart.plusDays(extraCycles * configuredLength);
        int cycleNumber = Math.toIntExact(profile.cycleNumber() + 1 + extraCycles);
        PlayerProfile updated = new PlayerProfile(profile.uuid(), profile.firstUseDate(), cycleNumber, start, configuredLength);
        int day = (int) ChronoUnit.DAYS.between(start, today) + 1;
        return new Resolution(updated, new CycleState(cycleNumber, start, configuredLength, day, today), true);
    }

    /**
     * Moves the player to {@code day} of their current cycle (after rolling forward) by moving
     * the cycle start date. The cycle number is unchanged, so every existing claim and
     * assignment for this cycle is preserved and still blocks re-claiming.
     */
    public static PlayerProfile setDay(PlayerProfile profile, LocalDate today, int day, int configuredLength) {
        PlayerProfile current = resolve(profile, today, configuredLength).profile();
        if (day < 1 || day > current.cycleLength()) {
            throw new IllegalArgumentException("day must be between 1 and " + current.cycleLength());
        }
        return new PlayerProfile(current.uuid(), current.firstUseDate(), current.cycleNumber(),
                today.minusDays(day - 1L), current.cycleLength());
    }

    /**
     * Starts a brand-new cycle at day 1 today. Because the cycle number increases, the player
     * can earn rewards again, including rewards for today's date.
     */
    public static PlayerProfile reset(PlayerProfile profile, LocalDate today, int configuredLength) {
        PlayerProfile current = resolve(profile, today, configuredLength).profile();
        return new PlayerProfile(current.uuid(), current.firstUseDate(), current.cycleNumber() + 1, today, configuredLength);
    }
}
