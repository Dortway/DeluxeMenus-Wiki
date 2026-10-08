package dev.exodaily.core.storage;

import dev.exodaily.core.reward.RewardPosition;

import java.util.UUID;

/** Unique identity of a claim: player, cycle, day and position. Enforced UNIQUE in the database. */
public record ClaimKey(UUID uuid, int cycle, int day, RewardPosition position) {

    public String asString() {
        return uuid + ":" + cycle + ":" + day + ":" + position.number();
    }

    @Override
    public String toString() {
        return asString();
    }
}
