package dev.exo.dailyspinner.storage;

/**
 * Lifecycle of a persisted spin.
 *
 * <pre>
 * RESERVED   -> entitlement consumed and reward chosen; nothing delivered yet (safe to deliver later)
 * DELIVERING -> delivery committed to start; inventory/commands are being applied this tick
 * DELIVERED  -> delivery finished (overflow items moved to pending storage)
 * UNCERTAIN  -> server stopped while DELIVERING; needs administrator reconciliation
 * FAILED     -> reward data could not be used (e.g. unreadable item); nothing was delivered
 * RESOLVED   -> an administrator reconciled an UNCERTAIN/FAILED spin
 * </pre>
 */
public enum SpinStatus {
    RESERVED,
    DELIVERING,
    DELIVERED,
    UNCERTAIN,
    FAILED,
    RESOLVED
}
