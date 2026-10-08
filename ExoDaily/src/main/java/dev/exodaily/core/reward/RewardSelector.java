package dev.exodaily.core.reward;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Independent weighted selection of a day's three rewards for one player. Selection has no
 * global state: two players can receive the same reward by coincidence.
 */
public final class RewardSelector {

    private RewardSelector() {
    }

    /**
     * @param position         claim position
     * @param poolId           pool the reward was drawn from (may be a fallback pool)
     * @param reward           selected reward
     * @param fallbackUsed     a fallback pool was used because the scheduled pool had no eligible entry
     * @param duplicateAllowed duplicate prevention had to be relaxed because every pool in the chain was exhausted
     */
    public record Selection(RewardPosition position, String poolId, RewardDefinition reward,
                            boolean fallbackUsed, boolean duplicateAllowed) {
    }

    public static Map<RewardPosition, Selection> select(RewardCatalog catalog, int day, RandomGenerator random) {
        DaySchedule schedule = catalog.scheduleFor(day);
        Map<RewardPosition, Selection> result = new EnumMap<>(RewardPosition.class);
        Set<String> used = new HashSet<>();
        for (RewardPosition position : RewardPosition.values()) {
            Selection selection = selectOne(catalog, schedule.poolFor(position), position, used, random);
            used.add(selection.reward().id());
            result.put(position, selection);
        }
        return result;
    }

    private static Selection selectOne(RewardCatalog catalog, String poolId, RewardPosition position,
                                       Set<String> used, RandomGenerator random) {
        List<String> chain = chain(catalog, poolId);
        Set<String> excluded = catalog.preventDuplicates() ? used : Set.of();
        for (int i = 0; i < chain.size(); i++) {
            RewardPool pool = catalog.pools().get(chain.get(i));
            List<PoolEntry> eligible = new ArrayList<>();
            for (PoolEntry entry : pool.entries()) {
                if (!excluded.contains(entry.rewardId()) && catalog.rewards().containsKey(entry.rewardId())) {
                    eligible.add(entry);
                }
            }
            if (!eligible.isEmpty()) {
                PoolEntry picked = pick(eligible, random);
                return new Selection(position, pool.id(), catalog.rewards().get(picked.rewardId()), i > 0, false);
            }
        }
        // Every pool in the chain is exhausted by duplicate prevention: allow a duplicate rather
        // than leaving the position empty. Validation guarantees the scheduled pool is non-empty.
        RewardPool pool = catalog.pools().get(chain.getFirst());
        PoolEntry picked = pick(pool.entries(), random);
        return new Selection(position, pool.id(), catalog.rewards().get(picked.rewardId()), false, true);
    }

    /** The scheduled pool, then its fallback chain, then the global fallback pool; cycles are ignored. */
    static List<String> chain(RewardCatalog catalog, String poolId) {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        String current = poolId;
        while (current != null && catalog.pools().containsKey(current) && chain.add(current)) {
            current = catalog.pools().get(current).fallback();
        }
        if (catalog.fallbackPool() != null && catalog.pools().containsKey(catalog.fallbackPool())) {
            chain.add(catalog.fallbackPool());
        }
        if (chain.isEmpty()) {
            throw new IllegalStateException("pool '" + poolId + "' does not exist");
        }
        return List.copyOf(chain);
    }

    static PoolEntry pick(List<PoolEntry> entries, RandomGenerator random) {
        long total = 0;
        for (PoolEntry entry : entries) {
            total += entry.weight();
        }
        long roll = random.nextLong(total);
        for (PoolEntry entry : entries) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        return entries.getLast();
    }
}
