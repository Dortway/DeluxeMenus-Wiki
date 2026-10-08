package dev.exodaily.core.reward;

import java.util.EnumMap;
import java.util.Map;

/** Pool used for each claim position on a given day. */
public record DaySchedule(Map<RewardPosition, String> pools) {

    public DaySchedule {
        EnumMap<RewardPosition, String> copy = new EnumMap<>(RewardPosition.class);
        copy.putAll(pools);
        pools = Map.copyOf(copy);
    }

    public String poolFor(RewardPosition position) {
        return pools.get(position);
    }

    /** Returns a schedule where positions defined in {@code override} replace this schedule's pools. */
    public DaySchedule overlay(DaySchedule override) {
        if (override == null) {
            return this;
        }
        EnumMap<RewardPosition, String> merged = new EnumMap<>(RewardPosition.class);
        merged.putAll(pools);
        merged.putAll(override.pools());
        return new DaySchedule(merged);
    }
}
