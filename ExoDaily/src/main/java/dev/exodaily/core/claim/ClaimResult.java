package dev.exodaily.core.claim;

import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.storage.ClaimRecord;

/** Outcome plus the reward involved (when known) and the resulting claim record (when one exists). */
public record ClaimResult(ClaimOutcome outcome, RewardDefinition reward, ClaimRecord claim) {

    public static ClaimResult of(ClaimOutcome outcome) {
        return new ClaimResult(outcome, null, null);
    }
}
