package net.exoeconomy.core.reward;

import java.math.BigDecimal;
import java.util.*;

/**
 * Per-player daily earning caps, per activity and in total. Counts include rewards that are
 * accrued but not yet deposited, so batching cannot be used to exceed a cap. Server thread only.
 */
public final class DailyLimits {
    public static final String TOTAL = "*";
    private final Map<UUID, Map<String, BigDecimal>> earned = new HashMap<>();
    private final Set<Key> dirty = new LinkedHashSet<>();
    private long day;

    public record Key(UUID player, String activity) {}
    public record Row(UUID player, long day, String activity, BigDecimal amount) {}

    public DailyLimits(long day) { this.day = day; }

    public long day() { return day; }

    /** Starts a new day: counters reset. Returns true if the day changed. */
    public boolean roll(long today) {
        if (today == day) return false;
        day = today;
        earned.clear();
        dirty.clear();
        return true;
    }

    public void load(Row row) {
        if (row.day() == day) earned.computeIfAbsent(row.player(), k -> new HashMap<>()).put(row.activity(), row.amount());
    }

    public BigDecimal earned(UUID player, String activity) {
        return earned.getOrDefault(player, Map.of()).getOrDefault(activity, BigDecimal.ZERO);
    }

    /**
     * Returns how much of {@code amount} fits under the caps (0 means unlimited) and records it.
     */
    public BigDecimal take(UUID player, String activity, BigDecimal amount, BigDecimal activityCap, BigDecimal totalCap) {
        BigDecimal allowed = amount;
        if (activityCap.signum() > 0) allowed = allowed.min(activityCap.subtract(earned(player, activity)).max(BigDecimal.ZERO));
        if (totalCap.signum() > 0) allowed = allowed.min(totalCap.subtract(earned(player, TOTAL)).max(BigDecimal.ZERO));
        if (allowed.signum() <= 0) return BigDecimal.ZERO;
        Map<String, BigDecimal> mine = earned.computeIfAbsent(player, k -> new HashMap<>());
        mine.merge(activity, allowed, BigDecimal::add);
        mine.merge(TOTAL, allowed, BigDecimal::add);
        dirty.add(new Key(player, activity));
        dirty.add(new Key(player, TOTAL));
        return allowed;
    }

    /** Gives back an amount that could not be deposited. */
    public void giveBack(UUID player, String activity, BigDecimal amount) {
        Map<String, BigDecimal> mine = earned.get(player);
        if (mine == null) return;
        mine.computeIfPresent(activity, (k, v) -> v.subtract(amount).max(BigDecimal.ZERO));
        mine.computeIfPresent(TOTAL, (k, v) -> v.subtract(amount).max(BigDecimal.ZERO));
        dirty.add(new Key(player, activity));
        dirty.add(new Key(player, TOTAL));
    }

    public List<Row> drainDirty() {
        List<Row> rows = new ArrayList<>();
        for (Key k : dirty) rows.add(new Row(k.player(), day, k.activity(), earned(k.player(), k.activity())));
        dirty.clear();
        return rows;
    }

    public void forget(UUID player) {
        if (dirty.stream().noneMatch(k -> k.player().equals(player))) earned.remove(player);
    }
}
