package dev.exo.dailyspinner.storage;

/** Which entitlement is used first when a player has both a ready daily spin and bonus spins. */
public enum ConsumeOrder {
    /** Default: the free daily spin is used first, bonus spins are kept for later. */
    DAILY_FIRST,
    BONUS_FIRST;

    public SpinSource choose(boolean dailyReady, boolean hasBonus) {
        if (this == DAILY_FIRST) {
            if (dailyReady) {
                return SpinSource.DAILY;
            }
            return hasBonus ? SpinSource.BONUS : null;
        }
        if (hasBonus) {
            return SpinSource.BONUS;
        }
        return dailyReady ? SpinSource.DAILY : null;
    }
}
