package dev.exo.dailyspinner.storage;

import dev.exo.dailyspinner.reward.RewardSnapshot;

import java.util.UUID;

public record SpinRecord(String id, UUID player, long createdAt, SpinSource source, SpinStatus status,
                         RewardSnapshot snapshot) {
}
