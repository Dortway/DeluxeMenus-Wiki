package dev.exoquests.core.shop;

/** Outcome of a purchase attempt as shown to the player. */
public record PurchaseResult(Status status, long balance, String purchaseId, String itemId) {

    public enum Status {
        /** Paid and delivered. */
        SUCCESS,
        /** Nothing charged. */
        INSUFFICIENT_FUNDS, PRICE_CHANGED, REWARD_CHANGED, UNAVAILABLE, NO_PERMISSION, NO_SPACE, OFFLINE,
        INVALID_NAME, BUSY, ERROR,
        /** Charged, then refunded because the reward could no longer be delivered. */
        REFUNDED,
        /** Charged; delivery is pending and will be attempted when the player is next online. */
        QUEUED,
        /** Charged; delivery outcome unknown, flagged for staff review. */
        NEEDS_REVIEW
    }

    static PurchaseResult of(Status s) {
        return new PurchaseResult(s, -1, null, null);
    }
}
