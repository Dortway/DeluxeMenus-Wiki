package dev.exoquests.core.storage;

/**
 * Durable purchase lifecycle.
 *
 * <pre>
 * PENDING ──(delivery started)──▶ DELIVERING ──▶ DELIVERED
 *    │                                │
 *    └──(no space / reward gone)──▶ REFUNDED     └──(crash or failure)──▶ NEEDS_REVIEW ──▶ RESOLVED | REFUNDED
 * </pre>
 *
 * PENDING means points were taken and nothing was handed out yet, so it is safe to deliver or refund.
 * DELIVERING means hand-out may have happened; it is never retried automatically.
 */
public enum PurchaseState { PENDING, DELIVERING, DELIVERED, REFUNDED, NEEDS_REVIEW, RESOLVED }
