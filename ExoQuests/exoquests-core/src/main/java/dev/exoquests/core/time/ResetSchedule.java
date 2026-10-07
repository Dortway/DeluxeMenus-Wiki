package dev.exoquests.core.time;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Defines the daily quest "period": the interval between two consecutive resets.
 *
 * <p>A period is identified by the local date on which it started. The reset boundary for a date
 * {@code d} is {@code d} at {@link #resetTime()} in {@link #zone()}. Daylight-saving transitions are
 * resolved by {@link ZonedDateTime#of}: a reset time that falls in a spring-forward gap is moved
 * forward by the length of the gap, and a reset time inside an autumn overlap uses the earlier
 * offset, so each local date has exactly one boundary instant.</p>
 *
 * <p>All methods are pure and thread-safe.</p>
 */
public record ResetSchedule(LocalTime resetTime, ZoneId zone) {

    public ResetSchedule {
        Objects.requireNonNull(resetTime, "resetTime");
        Objects.requireNonNull(zone, "zone");
    }

    public static ResetSchedule parse(String time, String zoneId) {
        LocalTime t;
        try {
            t = LocalTime.parse(time.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid reset time '" + time + "', expected HH:mm", e);
        }
        ZoneId z;
        try {
            z = ZoneId.of(zoneId.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid timezone '" + zoneId + "'", e);
        }
        return new ResetSchedule(t.withSecond(0).withNano(0), z);
    }

    /** Instant at which the period that starts on {@code date} begins. */
    public Instant boundary(LocalDate date) {
        return ZonedDateTime.of(date, resetTime, zone).toInstant();
    }

    /**
     * The period active at {@code now}: the latest date whose boundary is at or before {@code now}.
     */
    public LocalDate periodAt(Instant now) {
        LocalDate date = now.atZone(zone).toLocalDate();
        // Step back while the boundary for this date lies in the future.
        while (boundary(date).isAfter(now)) {
            date = date.minusDays(1);
        }
        // Step forward if a later date's boundary has already passed (guards zones that skip days).
        while (!boundary(date.plusDays(1)).isAfter(now)) {
            date = date.plusDays(1);
        }
        return date;
    }

    /** Period key used for persistence (ISO-8601 local date). */
    public String periodKey(Instant now) {
        return periodAt(now).toString();
    }

    /** First reset strictly after {@code now}. */
    public Instant nextReset(Instant now) {
        LocalDate next = periodAt(now).plusDays(1);
        Instant b = boundary(next);
        // A zone that skips a calendar day could map two dates to the same instant.
        while (!b.isAfter(now)) {
            next = next.plusDays(1);
            b = boundary(next);
        }
        return b;
    }

    public Duration untilNextReset(Instant now) {
        return Duration.between(now, nextReset(now));
    }
}
