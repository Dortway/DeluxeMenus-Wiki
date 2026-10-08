package dev.exodaily.core.claim;

import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.storage.ClaimKey;

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
     * Gives the reward. Items are given all-or-nothing and must never be dropped on the ground: if
     * they do not fit, the inventory must be left unchanged and
     * {@link DeliveryResult#NOT_DELIVERED_NO_SPACE} returned. Commands run after the items and
     * cannot be undone; if any command fails, return {@link DeliveryResult#UNCERTAIN}.
     *
     * @param key the unique claim identity, exposed to commands as {@code {claim_id}} so external
     *            integrations can ignore repeats
     */
    DeliveryResult deliver(RewardDefinition reward, ClaimKey key);

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
