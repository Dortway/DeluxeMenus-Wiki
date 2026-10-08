package dev.exodaily.core.claim;

import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.storage.ClaimKey;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A claim of one position, carrying the cycle, day and date the player's menu was rendered
 * for. These are only expectations: everything is revalidated server-side before delivery.
 */
public record ClaimRequest(UUID uuid, int cycle, int day, LocalDate date, RewardPosition position) {

    public ClaimKey key() {
        return new ClaimKey(uuid, cycle, day, position);
    }
}
