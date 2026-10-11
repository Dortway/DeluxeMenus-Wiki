package net.exoeconomy.core.booster;

import net.exoeconomy.api.BoosterScope;
import net.exoeconomy.core.Money;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * The economic definition of a booster tier: what a player is offered and pays for.
 * Immutable, so a confirmation menu keeps the exact offer even if configuration is reloaded.
 */
public record BoosterSpec(String tierId, BoosterScope scope, String activity, BigDecimal multiplier,
                          long durationMs, BigDecimal price) {
    public static final String ALL = "all";

    public BoosterSpec {
        if (tierId == null || !tierId.matches("[a-z0-9_\\-]{1,48}")) throw new IllegalArgumentException("Invalid tier id: " + tierId);
        activity = activity.toLowerCase(Locale.ROOT);
        if (!activity.matches("[a-z0-9_\\-]{1,32}")) throw new IllegalArgumentException("Invalid activity: " + activity);
        if (multiplier.compareTo(BigDecimal.ONE) <= 0 || multiplier.compareTo(Money.MAX_MULTIPLIER) > 0)
            throw new IllegalArgumentException("Booster multiplier must be above 1 and at most 100");
        if (durationMs < 1000 || durationMs > 366L * 24 * 3600 * 1000) throw new IllegalArgumentException("Duration must be 1s..366d");
        if (price.signum() < 0 || price.compareTo(Money.MAX_OPERATION) > 0) throw new IllegalArgumentException("Invalid price");
    }

    public boolean covers(String otherActivity) { return overlaps(activity, otherActivity); }

    /** Two activity scopes overlap if they are equal or either covers all activities. */
    public static boolean overlaps(String a, String b) {
        return a.equals(b) || ALL.equals(a) || ALL.equals(b);
    }
}
