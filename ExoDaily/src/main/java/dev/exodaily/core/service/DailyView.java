package dev.exodaily.core.service;

import dev.exodaily.core.progression.CycleState;
import dev.exodaily.core.progression.PlayerProfile;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.storage.AssignmentRecord;
import dev.exodaily.core.storage.ClaimRecord;
import dev.exodaily.core.storage.ClaimState;

import java.util.Map;

/**
 * Immutable snapshot of everything a menu needs for one player and one date. Rendering never
 * touches storage or reward selection; it only reads this view.
 */
public record DailyView(
        PlayerProfile profile,
        CycleState state,
        Map<RewardPosition, AssignmentRecord> assignments,
        Map<RewardPosition, ClaimRecord> claims,
        PreviousDay previousDay,
        Map<Integer, Integer> cycleClaimCounts
) {

    public DailyView {
        assignments = Map.copyOf(assignments);
        claims = Map.copyOf(claims);
        cycleClaimCounts = Map.copyOf(cycleClaimCounts);
    }

    public PositionStatus status(RewardPosition position, boolean premium) {
        ClaimRecord claim = claims.get(position);
        if (claim != null) {
            return switch (claim.state()) {
                case DELIVERED -> PositionStatus.CLAIMED;
                case UNCERTAIN -> PositionStatus.REVIEW;
                case RESERVED, DELIVERING -> PositionStatus.PROCESSING;
            };
        }
        if (position.requiresPremium() && !premium) {
            return PositionStatus.LOCKED;
        }
        return PositionStatus.AVAILABLE;
    }

    /** Delivered claims today, counted against the tier's limit. */
    public int claimedCount(boolean premium) {
        int count = 0;
        for (RewardPosition position : RewardPosition.values()) {
            if (position.requiresPremium() && !premium) {
                continue;
            }
            ClaimRecord claim = claims.get(position);
            if (claim != null && claim.state() == ClaimState.DELIVERED) {
                count++;
            }
        }
        return count;
    }

    public int availableCount(boolean premium) {
        int count = 0;
        for (RewardPosition position : RewardPosition.values()) {
            if (status(position, premium) == PositionStatus.AVAILABLE) {
                count++;
            }
        }
        return count;
    }

    /** Returns a copy with one claim replaced, used to update open menus after a claim. */
    public DailyView withClaim(ClaimRecord claim) {
        java.util.EnumMap<RewardPosition, ClaimRecord> updated = new java.util.EnumMap<>(RewardPosition.class);
        updated.putAll(claims);
        updated.put(claim.position(), claim);
        return new DailyView(profile, state, assignments, updated, previousDay, cycleClaimCounts);
    }
}
