package dev.exodaily.core.storage;

import dev.exodaily.core.reward.RewardPosition;

import java.time.LocalDate;
import java.util.UUID;

/** A persisted claim and its delivery record. */
public record ClaimRecord(
        long id,
        UUID uuid,
        int cycle,
        int day,
        RewardPosition position,
        LocalDate rewardDate,
        String rewardId,
        ClaimState state,
        String attemptId,
        long reservedAt,
        Long deliveringAt,
        Long deliveredAt,
        String note
) {

    public ClaimKey key() {
        return new ClaimKey(uuid, cycle, day, position);
    }
}
