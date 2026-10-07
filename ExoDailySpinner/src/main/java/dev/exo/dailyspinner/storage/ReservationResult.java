package dev.exo.dailyspinner.storage;

/** Outcome of an atomic spin reservation. */
public record ReservationResult(Outcome outcome, SpinRecord spin, long remainingMillis) {

    public enum Outcome {
        /** A new spin was reserved and the entitlement consumed. */
        RESERVED,
        /** An undelivered spin already exists; it is returned instead of rolling again. */
        EXISTING,
        /** No daily spin is ready and no bonus spins remain. */
        UNAVAILABLE,
        /** The operation id was already used (replayed request). Nothing changed. */
        DUPLICATE
    }

    public static ReservationResult reserved(SpinRecord spin) {
        return new ReservationResult(Outcome.RESERVED, spin, 0);
    }

    public static ReservationResult existing(SpinRecord spin) {
        return new ReservationResult(Outcome.EXISTING, spin, 0);
    }

    public static ReservationResult unavailable(long remaining) {
        return new ReservationResult(Outcome.UNAVAILABLE, null, remaining);
    }

    public static ReservationResult duplicate() {
        return new ReservationResult(Outcome.DUPLICATE, null, 0);
    }
}
