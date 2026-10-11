package net.exoeconomy.core.booster;

import net.exoeconomy.api.BoosterInfo;
import net.exoeconomy.api.BoosterScope;

import java.math.BigDecimal;
import java.util.UUID;

/** Mutable booster record, confined to the server thread inside {@link BoosterBook}. */
public final class Booster {
    public final String id;
    public final String tierId;
    public final BoosterScope scope;
    public final UUID owner;
    public final String activity;
    public final BigDecimal multiplier;
    public final String sourceTx;
    public final long createdAt;
    public BoosterState state;
    public long expiresAt;
    public long remainingMs;
    public long sequence;
    /** True when paused only because the server stopped (resumed on the next start). */
    public boolean pausedByServer;

    public Booster(String id, String tierId, BoosterScope scope, UUID owner, String activity, BigDecimal multiplier,
                   String sourceTx, long createdAt, BoosterState state, long expiresAt, long remainingMs, long sequence,
                   boolean pausedByServer) {
        if ((scope == BoosterScope.PERSONAL) != (owner != null)) throw new IllegalArgumentException("Personal boosters need an owner; global ones must not have one");
        this.id = id; this.tierId = tierId; this.scope = scope; this.owner = owner; this.activity = activity;
        this.multiplier = multiplier; this.sourceTx = sourceTx; this.createdAt = createdAt; this.state = state;
        this.expiresAt = expiresAt; this.remainingMs = remainingMs; this.sequence = sequence; this.pausedByServer = pausedByServer;
    }

    public long remaining(long now) { return state == BoosterState.ACTIVE ? Math.max(0, expiresAt - now) : remainingMs; }

    public boolean covers(String activityId) { return BoosterSpec.overlaps(activity, activityId); }

    public BoosterInfo view() {
        return new BoosterInfo(id, tierId, scope, owner, activity, multiplier, state.name(), expiresAt, remainingMs);
    }

    Booster copy() {
        return new Booster(id, tierId, scope, owner, activity, multiplier, sourceTx, createdAt, state, expiresAt, remainingMs, sequence, pausedByServer);
    }
}
