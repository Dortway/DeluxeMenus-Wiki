package dev.exodaily.core.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Calendar-aware clock. Every reward day is a calendar date in the configured zone, so
 * days are not assumed to last 24 hours: daylight-saving days last 23 or 25 hours and the
 * next reset is always the start of the next calendar date in that zone.
 */
public final class DailyClock {

    private final Clock clock;
    private volatile ZoneId zone;

    public DailyClock(Clock clock, ZoneId zone) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public ZoneId zone() {
        return zone;
    }

    public void zone(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public Instant instant() {
        return clock.instant();
    }

    public ZonedDateTime now() {
        return ZonedDateTime.ofInstant(clock.instant(), zone);
    }

    public LocalDate today() {
        return now().toLocalDate();
    }

    /** Start of the next calendar date, resolving midnight DST gaps to the first valid instant. */
    public ZonedDateTime nextReset() {
        return nextReset(now());
    }

    public Duration untilReset() {
        ZonedDateTime now = now();
        return Duration.between(now, nextReset(now));
    }

    public static ZonedDateTime nextReset(ZonedDateTime now) {
        return now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());
    }
}
