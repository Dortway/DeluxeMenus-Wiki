package dev.exodaily.core.storage;

import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.reward.RewardPosition;

import java.time.LocalDate;
import java.util.UUID;

/** A reward assigned to one position of one player's day, with its frozen snapshot. */
public record AssignmentRecord(
        UUID uuid,
        int cycle,
        int day,
        RewardPosition position,
        LocalDate rewardDate,
        String poolId,
        RewardDefinition reward,
        long createdAt
) {
}
