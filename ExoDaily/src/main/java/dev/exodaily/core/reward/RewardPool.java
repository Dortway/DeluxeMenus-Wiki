package dev.exodaily.core.reward;

import java.util.List;

/**
 * A weighted pool of rewards.
 *
 * @param id          pool identifier
 * @param description MiniMessage text shown in future-day previews
 * @param entries     weighted entries (never empty after validation)
 * @param fallback    pool used when this pool has no eligible entry left, or null
 */
public record RewardPool(String id, String description, List<PoolEntry> entries, String fallback) {

    public RewardPool {
        entries = List.copyOf(entries);
    }
}
