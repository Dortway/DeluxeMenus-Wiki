package net.exoeconomy.core.booster;

public enum BoosterState {
    /** Running; expires at {@code expiresAt}. */
    ACTIVE,
    /** Waiting for an overlapping booster to end; keeps its full duration in {@code remainingMs}. */
    QUEUED,
    /** Frozen with {@code remainingMs} left (owner offline in PAUSE mode, or server stopped in PAUSE mode). */
    PAUSED,
    EXPIRED,
    CANCELLED;

    public boolean live() { return this == ACTIVE || this == PAUSED; }
    public boolean pending() { return this == ACTIVE || this == PAUSED || this == QUEUED; }
}
