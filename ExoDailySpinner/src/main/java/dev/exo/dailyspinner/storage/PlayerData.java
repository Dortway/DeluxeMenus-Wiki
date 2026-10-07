package dev.exo.dailyspinner.storage;

import java.util.UUID;

/**
 * Snapshot of a player's spin state.
 *
 * @param lastDailySpin epoch millis of last daily spin, {@code null} when never used or reset
 * @param activeSpin    {@code true} when a reserved/delivering spin exists
 */
public record PlayerData(UUID player, Long lastDailySpin, int bonusSpins, int pendingItems, boolean activeSpin) {
}
