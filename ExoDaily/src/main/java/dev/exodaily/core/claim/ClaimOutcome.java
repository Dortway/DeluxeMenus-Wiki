package dev.exodaily.core.claim;

/** Final result of a claim attempt. Only {@link #SUCCESS} and {@link #DELIVERED_UNCONFIRMED} gave items. */
public enum ClaimOutcome {
    SUCCESS,
    /** Items were given, but recording DELIVERED failed; the claim stays blocked and is flagged for review. */
    DELIVERED_UNCONFIRMED,
    ALREADY_CLAIMED,
    LOCKED,
    /** The menu no longer matches today's cycle and day (midnight passed, admin change, reset). */
    STALE,
    INVENTORY_FULL,
    /** The reward item could not be built; it stays available. */
    DELIVERY_FAILED,
    PENDING_REVIEW,
    IN_PROGRESS,
    OFFLINE,
    NOT_ASSIGNED,
    STORAGE_ERROR,
    BUSY;

    public boolean delivered() {
        return this == SUCCESS || this == DELIVERED_UNCONFIRMED;
    }
}
