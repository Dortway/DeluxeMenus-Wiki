package dev.exodaily.core.claim;

import dev.exodaily.core.reward.RewardDefinition;

/**
 * The player side of a claim. Every method is invoked on the server thread.
 */
public interface ClaimParticipant {

    boolean isOnline();

    /** Current premium permission, checked live on every claim. */
    boolean hasPremium();

    /** Verifies the reward can be built and fits completely, without giving anything. */
    DeliveryCheck check(RewardDefinition reward);

    /**
     * Gives the reward all-or-nothing. Items must never be dropped on the ground. If the items do
     * not fit, the inventory must be left unchanged and {@link DeliveryResult#NOT_DELIVERED_NO_SPACE}
     * returned. Return {@link DeliveryResult#UNCERTAIN} only if the inventory state is unknown.
     */
    DeliveryResult deliver(RewardDefinition reward);

    enum DeliveryCheck {
        OK,
        NO_SPACE,
        INVALID_ITEM
    }

    enum DeliveryResult {
        DELIVERED,
        NOT_DELIVERED_NO_SPACE,
        NOT_DELIVERED_INVALID,
        UNCERTAIN
    }
}
