package net.exoeconomy.core.reward;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Collects activity rewards between deposits so one ExcellentEconomy call pays for many blocks or
 * kills. Fractions below the currency precision carry over to the next deposit instead of being
 * rounded up or lost. Server thread only.
 */
public final class RewardAccumulator {
    private final Map<UUID, Pending> pending = new HashMap<>();

    public static final class Pending {
        BigDecimal total = BigDecimal.ZERO;
        final Map<String, BigDecimal> byActivity = new LinkedHashMap<>();
        int failures;
    }

    /** What a deposit should pay now, broken down by activity for the notification. */
    public record Payout(UUID player, BigDecimal amount, Map<String, BigDecimal> byActivity) {}

    public void add(UUID player, String activity, BigDecimal amount) {
        if (amount.signum() <= 0) return;
        Pending p = pending.computeIfAbsent(player, k -> new Pending());
        p.total = p.total.add(amount);
        p.byActivity.merge(activity, amount, BigDecimal::add);
    }

    /** Takes the payable part (rounded down to {@code scale}) and keeps the remainder. */
    public Optional<Payout> take(UUID player, int scale) {
        Pending p = pending.get(player);
        if (p == null) return Optional.empty();
        BigDecimal payable = p.total.setScale(scale, RoundingMode.DOWN);
        if (payable.signum() <= 0) return Optional.empty();
        Map<String, BigDecimal> breakdown = new LinkedHashMap<>(p.byActivity);
        p.total = p.total.subtract(payable);
        p.byActivity.clear();
        return Optional.of(new Payout(player, payable, breakdown));
    }

    /** Puts a payout back after the economy refused it; returns the number of consecutive failures. */
    public int restore(Payout payout) {
        Pending p = pending.computeIfAbsent(payout.player(), k -> new Pending());
        p.total = p.total.add(payout.amount());
        payout.byActivity().forEach((k, v) -> p.byActivity.merge(k, v, BigDecimal::add));
        return ++p.failures;
    }

    public void succeeded(UUID player) {
        Pending p = pending.get(player);
        if (p != null) p.failures = 0;
    }

    /** Drops everything for a player (logout after the final flush); returns the discarded remainder. */
    public BigDecimal remove(UUID player) {
        Pending p = pending.remove(player);
        return p == null ? BigDecimal.ZERO : p.total;
    }

    public Set<UUID> players() { return new HashSet<>(pending.keySet()); }

    public BigDecimal pending(UUID player) {
        Pending p = pending.get(player);
        return p == null ? BigDecimal.ZERO : p.total;
    }
}
