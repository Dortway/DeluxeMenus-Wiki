package dev.exodaily.core.progression;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted progression for one player.
 *
 * @param uuid         player identity
 * @param firstUseDate calendar date of the player's very first {@code /daily}
 * @param cycleNumber  monotonically increasing cycle identifier; never reused, so claims of
 *                     earlier cycles can never collide with claims of later cycles
 * @param cycleStart   calendar date that is day 1 of the current cycle
 * @param cycleLength  length of the current cycle, fixed when the cycle started
 */
public record PlayerProfile(UUID uuid, LocalDate firstUseDate, int cycleNumber, LocalDate cycleStart, int cycleLength) {

    public PlayerProfile {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(firstUseDate, "firstUseDate");
        Objects.requireNonNull(cycleStart, "cycleStart");
        if (cycleNumber < 1) {
            throw new IllegalArgumentException("cycleNumber must be >= 1");
        }
        if (cycleLength < 1) {
            throw new IllegalArgumentException("cycleLength must be >= 1");
        }
    }
}
