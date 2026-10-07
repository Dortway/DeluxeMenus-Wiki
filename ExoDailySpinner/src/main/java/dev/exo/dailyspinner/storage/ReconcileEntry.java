package dev.exo.dailyspinner.storage;

import java.util.UUID;

/**
 * An ambiguous or failed delivery that needs an administrator decision.
 *
 * @param key {@code S:<spin id>} for spins or {@code P:<pending id>} for pending item claims
 */
public record ReconcileEntry(String key, UUID player, String rewardId, String kind, String status,
                             long updatedAt, String detail) {
}
