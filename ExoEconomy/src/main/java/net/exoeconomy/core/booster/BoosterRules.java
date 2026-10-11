package net.exoeconomy.core.booster;

import java.math.BigDecimal;

/**
 * Stacking and timing rules from boosters.yml.
 *
 * @param sameTier             what buying a tier that is already active (or paused) for the same owner does
 * @param overlapping          what buying a different booster that covers an overlapping activity does
 * @param maxLivePerOwner      max active+paused boosters per owner (the global pool counts as one owner)
 * @param maxQueuedPerOwner    max queued boosters per owner
 * @param maxRemainingMs       an EXTEND may not push the remaining time above this
 * @param combine              how simultaneous multipliers combine
 * @param maxTotalMultiplier   hard cap on the combined booster multiplier
 * @param personalOffline      whether personal boosters keep running while the owner is offline
 * @param serverOffline        whether boosters keep running while the server is stopped (clean shutdowns)
 */
public record BoosterRules(SameTier sameTier, Overlap overlapping, int maxLivePerOwner, int maxQueuedPerOwner,
                           long maxRemainingMs, CombineMode combine, BigDecimal maxTotalMultiplier,
                           OfflineTime personalOffline, OfflineTime serverOffline) {
    public enum SameTier { EXTEND, QUEUE, STACK, REJECT }
    public enum Overlap { STACK, QUEUE, REJECT }
    public enum OfflineTime { CONTINUE, PAUSE }

    public BoosterRules {
        if (maxLivePerOwner < 1 || maxQueuedPerOwner < 0 || maxRemainingMs < 1000) throw new IllegalArgumentException("Invalid booster limits");
        if (maxTotalMultiplier.compareTo(BigDecimal.ONE) < 0 || maxTotalMultiplier.compareTo(net.exoeconomy.core.Money.MAX_MULTIPLIER) > 0)
            throw new IllegalArgumentException("max-total-multiplier must be between 1 and 100");
    }

    public static BoosterRules defaults() {
        return new BoosterRules(SameTier.EXTEND, Overlap.STACK, 3, 3, 6 * 3600_000L, CombineMode.ADDITIVE,
                new BigDecimal("4"), OfflineTime.CONTINUE, OfflineTime.CONTINUE);
    }
}
