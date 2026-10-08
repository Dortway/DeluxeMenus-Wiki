package dev.exodaily.core.reward;

import java.util.Optional;

/**
 * The three numbered claim positions of a day. Standard players can only claim position 1;
 * premium players can claim all three, so premium means at most 3 rewards per day in total.
 */
public enum RewardPosition {
    STANDARD(1, false),
    PREMIUM_ONE(2, true),
    PREMIUM_TWO(3, true);

    private final int number;
    private final boolean requiresPremium;

    RewardPosition(int number, boolean requiresPremium) {
        this.number = number;
        this.requiresPremium = requiresPremium;
    }

    public int number() {
        return number;
    }

    public boolean requiresPremium() {
        return requiresPremium;
    }

    public static Optional<RewardPosition> fromNumber(int number) {
        for (RewardPosition position : values()) {
            if (position.number == number) {
                return Optional.of(position);
            }
        }
        return Optional.empty();
    }

    public static int claimableCount(boolean premium) {
        int count = 0;
        for (RewardPosition position : values()) {
            if (!position.requiresPremium || premium) {
                count++;
            }
        }
        return count;
    }
}
