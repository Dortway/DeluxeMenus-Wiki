package dev.exodaily.core.reward;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validated, immutable view of rewards.yml.
 *
 * @param rewards            reward definitions by id
 * @param pools              pools by id
 * @param defaults           schedule used for days without overrides
 * @param days               per-day overrides (milestones etc.)
 * @param preventDuplicates  never give the same reward id twice within one player's day
 * @param fallbackPool       last-resort pool when a pool chain runs out of eligible entries, or null
 * @param previewMaxEntries  how many possible rewards future-day previews list per pool
 * @param milestoneDays      days highlighted as milestones in menus
 */
public record RewardCatalog(
        Map<String, RewardDefinition> rewards,
        Map<String, RewardPool> pools,
        DaySchedule defaults,
        Map<Integer, DaySchedule> days,
        boolean preventDuplicates,
        String fallbackPool,
        int previewMaxEntries,
        Set<Integer> milestoneDays
) {

    public RewardCatalog {
        rewards = Map.copyOf(rewards);
        pools = Map.copyOf(pools);
        days = Map.copyOf(days);
        milestoneDays = Set.copyOf(milestoneDays);
    }

    public DaySchedule scheduleFor(int day) {
        return defaults.overlay(days.get(day));
    }

    public Optional<RewardDefinition> reward(String id) {
        return Optional.ofNullable(rewards.get(id));
    }

    public Optional<RewardPool> pool(String id) {
        return Optional.ofNullable(id == null ? null : pools.get(id));
    }
}
