package dev.exodaily.core.service;

import java.time.LocalDate;

/** What happened on the previous calendar date. */
public record PreviousDay(Kind kind, LocalDate date, int claimed) {

    public enum Kind {
        /** The player had not started yet. */
        NONE,
        /** At least one reward was claimed. */
        CLAIMED,
        /** Nothing was claimed; the day's rewards were skipped permanently. */
        MISSED
    }
}
