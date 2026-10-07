package dev.exoquests.core.util;

import java.util.Objects;

/** Immutable block coordinate inside a world identified by its UUID string. */
public record BlockPos(String world, int x, int y, int z) {

    public BlockPos {
        Objects.requireNonNull(world, "world");
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(world, x + dx, y + dy, z + dz);
    }

    public BlockPos above() {
        return offset(0, 1, 0);
    }
}
