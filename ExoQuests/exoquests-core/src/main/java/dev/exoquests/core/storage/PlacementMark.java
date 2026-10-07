package dev.exoquests.core.storage;

import dev.exoquests.core.util.BlockPos;
import java.util.UUID;

/**
 * A persisted record that a block was put in place by a player (or moved there by a piston).
 *
 * @param owner planting player for saplings that may earn tree credit, otherwise {@code null}
 */
public record PlacementMark(BlockPos pos, String material, Kind kind, UUID owner, long placedAt) {

    public enum Kind {
        /** Player-placed block; breaking it never earns natural-only progress. */
        PLACED,
        /** Player-planted sapling whose growth can be credited to {@code owner}. */
        SAPLING
    }
}
