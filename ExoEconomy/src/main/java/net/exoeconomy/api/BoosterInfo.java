package net.exoeconomy.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable view of a booster.
 *
 * @param id          unique booster id
 * @param tierId      tier from boosters.yml, or "admin" for custom admin/API grants
 * @param scope       personal or global
 * @param owner       owner for personal boosters, null for global boosters
 * @param activity    activity id it covers ("all", "mining", "farming", "slaying", "selling" or a custom id)
 * @param multiplier  multiplier applied to covered base rewards, between 1 and 100
 * @param state       ACTIVE, QUEUED, PAUSED, EXPIRED or CANCELLED
 * @param expiresAt   epoch millis when an ACTIVE booster ends, 0 otherwise
 * @param remainingMs remaining time for QUEUED or PAUSED boosters
 */
public record BoosterInfo(String id, String tierId, BoosterScope scope, UUID owner, String activity,
                          BigDecimal multiplier, String state, long expiresAt, long remainingMs) {
    public long remaining(long now) {
        return "ACTIVE".equals(state) ? Math.max(0, expiresAt - now) : remainingMs;
    }
}
