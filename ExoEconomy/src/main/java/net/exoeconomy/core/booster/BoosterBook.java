package net.exoeconomy.core.booster;

import net.exoeconomy.api.BoosterInfo;
import net.exoeconomy.api.BoosterScope;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * All boosters and their stacking, queueing, pausing and expiry rules.
 *
 * <p>Mutations happen on the server thread only. Reads from other threads (PlaceholderAPI) go
 * through {@link #multiplier} and {@link #snapshot}, which use an immutable snapshot replaced on
 * every change. Changed boosters are collected in a dirty set that the caller persists.
 *
 * <p>Purchase semantics:
 * <ul>
 *   <li>Same tier already active or paused for the owner: {@link BoosterRules.SameTier} decides
 *       (default EXTEND: add the duration, capped by max-remaining-duration).</li>
 *   <li>A different booster covering an overlapping activity is active: {@link BoosterRules.Overlap}
 *       decides (default STACK: both run and their multipliers combine).</li>
 *   <li>QUEUE stores the booster with its full duration; it starts automatically when no
 *       overlapping booster of the same owner (or the global pool) remains.</li>
 * </ul>
 */
public final class BoosterBook {
    private final Map<String, Booster> boosters = new LinkedHashMap<>();
    private final Set<String> dirty = new LinkedHashSet<>();
    private final LongSupplier clock;
    private BoosterRules rules;
    private long sequence;
    private volatile List<BoosterInfo> snapshot = List.of();

    public BoosterBook(BoosterRules rules, LongSupplier clock) {
        this.rules = Objects.requireNonNull(rules);
        this.clock = clock;
    }

    public BoosterRules rules() { return rules; }
    public void rules(BoosterRules rules) { this.rules = Objects.requireNonNull(rules); }

    /** Decides what buying {@code spec} for {@code owner} (null for global) would do right now. */
    public Plan plan(BoosterSpec spec, UUID owner) {
        List<Booster> mine = pool(spec.scope(), owner);
        List<Booster> live = mine.stream().filter(b -> b.state.live()).toList();
        long now = clock.getAsLong();
        Optional<Booster> same = live.stream().filter(b -> b.tierId.equals(spec.tierId()))
                .max(Comparator.comparingLong(b -> b.remaining(now)));
        boolean forceQueue = false;
        if (same.isPresent()) {
            switch (rules.sameTier()) {
                case EXTEND -> {
                    long after = same.get().remaining(now) + spec.durationMs();
                    return after > rules.maxRemainingMs() ? Plan.reject("booster-max-duration") : Plan.extend(same.get().id);
                }
                case REJECT -> { return Plan.reject("booster-already-active"); }
                case QUEUE -> forceQueue = true;
                case STACK -> { }
            }
        }
        boolean overlapping = live.stream().anyMatch(b -> b.covers(spec.activity()));
        if (forceQueue || (overlapping && rules.overlapping() == BoosterRules.Overlap.QUEUE)) {
            long queued = mine.stream().filter(b -> b.state == BoosterState.QUEUED).count();
            return queued >= rules.maxQueuedPerOwner() ? Plan.reject("booster-queue-full") : Plan.queue();
        }
        if (overlapping && rules.overlapping() == BoosterRules.Overlap.REJECT) return Plan.reject("booster-overlap");
        if (live.size() >= rules.maxLivePerOwner()) return Plan.reject("booster-too-many");
        return Plan.activate();
    }

    /**
     * Re-plans and applies a purchase or grant. Called after payment, so the state may have
     * changed since the player confirmed (an extended booster may have expired, a queue may have
     * drained); re-planning picks the right action for the current state.
     *
     * @throws IllegalStateException if the purchase is no longer allowed (the caller refunds)
     */
    public Booster apply(BoosterSpec spec, UUID owner, String id, String sourceTx, Set<UUID> onlinePlayers) {
        Plan plan = plan(spec, owner);
        long now = clock.getAsLong();
        switch (plan.kind()) {
            case REJECT -> throw new IllegalStateException(plan.reason());
            case EXTEND -> {
                Booster target = boosters.get(plan.targetId());
                if (target.state == BoosterState.ACTIVE) target.expiresAt += spec.durationMs();
                else target.remainingMs += spec.durationMs();
                changed(target);
                return target;
            }
            case QUEUE -> {
                Booster booster = create(spec, owner, id, sourceTx, now, BoosterState.QUEUED, 0, spec.durationMs());
                changed(booster);
                return booster;
            }
            default -> {
                Booster booster = create(spec, owner, id, sourceTx, now, BoosterState.ACTIVE, now + spec.durationMs(), 0);
                if (shouldPauseOffline(booster, onlinePlayers)) pause(booster, now, false);
                changed(booster);
                return booster;
            }
        }
    }

    private Booster create(BoosterSpec spec, UUID owner, String id, String sourceTx, long now, BoosterState state, long expiresAt, long remaining) {
        if (boosters.containsKey(id)) throw new IllegalStateException("Duplicate booster id " + id);
        Booster booster = new Booster(id, spec.tierId(), spec.scope(), spec.scope() == BoosterScope.PERSONAL ? owner : null,
                spec.activity(), spec.multiplier(), sourceTx, now, state, expiresAt, remaining, ++sequence, false);
        boosters.put(id, booster);
        return booster;
    }

    /** Expires finished boosters and starts queued ones. Returns what happened, for announcements. */
    public List<Change> tick(Set<UUID> onlinePlayers) {
        long now = clock.getAsLong();
        List<Change> changes = new ArrayList<>();
        for (Booster booster : boosters.values()) {
            if (booster.state == BoosterState.ACTIVE && booster.expiresAt <= now) {
                booster.state = BoosterState.EXPIRED;
                booster.remainingMs = 0;
                changed(booster);
                changes.add(new Change(Change.Type.EXPIRED, booster.view()));
            }
        }
        promoteQueued(now, onlinePlayers, changes);
        if (!changes.isEmpty()) publish();
        boosters.values().removeIf(b -> !b.state.pending() && !dirty.contains(b.id));
        return changes;
    }

    private void promoteQueued(long now, Set<UUID> online, List<Change> changes) {
        List<Booster> queued = boosters.values().stream().filter(b -> b.state == BoosterState.QUEUED)
                .sorted(Comparator.comparingLong(b -> b.sequence)).toList();
        for (Booster next : queued) {
            boolean blocked = pool(next.scope, next.owner).stream()
                    .anyMatch(b -> b != next && b.state.live() && b.covers(next.activity));
            if (blocked) continue;
            next.state = BoosterState.ACTIVE;
            next.expiresAt = now + next.remainingMs;
            next.remainingMs = 0;
            if (shouldPauseOffline(next, online)) pause(next, now, false);
            changed(next);
            changes.add(new Change(Change.Type.ACTIVATED, next.view()));
        }
    }

    /** Owner went offline: pause their personal boosters when personal-offline-time is PAUSE. */
    public void ownerOffline(UUID owner) {
        if (rules.personalOffline() != BoosterRules.OfflineTime.PAUSE) return;
        long now = clock.getAsLong();
        for (Booster b : pool(BoosterScope.PERSONAL, owner))
            if (b.state == BoosterState.ACTIVE) { pause(b, now, false); changed(b); }
        publish();
    }

    /** Owner came online: resume boosters paused while they were away. */
    public void ownerOnline(UUID owner) {
        long now = clock.getAsLong();
        for (Booster b : pool(BoosterScope.PERSONAL, owner))
            if (b.state == BoosterState.PAUSED && !b.pausedByServer) { resume(b, now); changed(b); }
        publish();
    }

    /**
     * Clean shutdown. With server-offline-time PAUSE, running boosters freeze until the next start.
     * Personal boosters in personal-offline-time PAUSE mode freeze because their owners are leaving.
     * A crash skips this, so time keeps counting through the crash (documented).
     */
    public void serverStopping() {
        long now = clock.getAsLong();
        for (Booster b : boosters.values()) {
            if (b.state != BoosterState.ACTIVE) continue;
            if (b.scope == BoosterScope.PERSONAL && rules.personalOffline() == BoosterRules.OfflineTime.PAUSE) {
                pause(b, now, false);
                changed(b);
            } else if (rules.serverOffline() == BoosterRules.OfflineTime.PAUSE) {
                pause(b, now, true);
                changed(b);
            }
        }
        publish();
    }

    /** Loads persisted boosters at startup and resumes server-paused ones. */
    public void restore(Collection<Booster> loaded, Set<UUID> onlinePlayers) {
        long now = clock.getAsLong();
        for (Booster b : loaded) {
            if (!b.state.pending()) continue;
            boosters.put(b.id, b);
            sequence = Math.max(sequence, b.sequence);
            if (b.state == BoosterState.PAUSED && b.pausedByServer) {
                resume(b, now);
                if (shouldPauseOffline(b, onlinePlayers)) pause(b, now, false);
                changed(b);
            } else if (b.state == BoosterState.PAUSED && b.scope == BoosterScope.PERSONAL
                    && (rules.personalOffline() == BoosterRules.OfflineTime.CONTINUE || onlinePlayers.contains(b.owner))) {
                resume(b, now);
                changed(b);
            }
        }
        publish();
    }

    /** Cancels a booster (admin). Returns the cancelled booster view, if it existed. */
    public Optional<BoosterInfo> cancel(String id, Set<UUID> onlinePlayers) {
        Booster b = boosters.get(id);
        if (b == null || !b.state.pending()) return Optional.empty();
        b.state = BoosterState.CANCELLED;
        b.remainingMs = 0;
        changed(b);
        promoteQueued(clock.getAsLong(), onlinePlayers, new ArrayList<>());
        publish();
        return Optional.of(b.view());
    }

    private boolean shouldPauseOffline(Booster b, Set<UUID> online) {
        return b.scope == BoosterScope.PERSONAL && rules.personalOffline() == BoosterRules.OfflineTime.PAUSE && !online.contains(b.owner);
    }

    private static void pause(Booster b, long now, boolean byServer) {
        b.remainingMs = Math.max(0, b.expiresAt - now);
        b.expiresAt = 0;
        b.state = BoosterState.PAUSED;
        b.pausedByServer = byServer;
    }

    private static void resume(Booster b, long now) {
        b.expiresAt = now + b.remainingMs;
        b.remainingMs = 0;
        b.state = BoosterState.ACTIVE;
        b.pausedByServer = false;
    }

    private List<Booster> pool(BoosterScope scope, UUID owner) {
        UUID key = scope == BoosterScope.PERSONAL ? owner : null;
        return boosters.values().stream().filter(b -> b.scope == scope && Objects.equals(b.owner, key) && b.state.pending()).toList();
    }

    private void changed(Booster b) {
        dirty.add(b.id);
        publish();
    }

    private void publish() {
        snapshot = boosters.values().stream().filter(b -> b.state.pending()).map(Booster::view).toList();
    }

    /** Removes and returns copies of every booster changed since the last call, for persistence. */
    public List<Booster> drainDirty() {
        List<Booster> out = new ArrayList<>();
        for (String id : dirty) {
            Booster b = boosters.get(id);
            if (b != null) out.add(b.copy());
        }
        dirty.clear();
        boosters.values().removeIf(b -> !b.state.pending());
        return out;
    }

    /** Thread-safe: all boosters that are active, paused or queued. */
    public List<BoosterInfo> snapshot() { return snapshot; }

    /** Thread-safe: boosters currently increasing {@code player}'s rewards for {@code activity}. */
    public List<BoosterInfo> applicable(UUID player, String activity) {
        long now = clock.getAsLong();
        List<BoosterInfo> out = new ArrayList<>();
        for (BoosterInfo b : snapshot) {
            if (!"ACTIVE".equals(b.state()) || b.expiresAt() <= now) continue;
            if (b.scope() == BoosterScope.PERSONAL && !b.owner().equals(player)) continue;
            if (BoosterSpec.overlaps(b.activity(), activity) && !(BoosterSpec.ALL.equals(b.activity()) && "selling".equals(activity))) out.add(b);
        }
        return out;
    }

    /**
     * Thread-safe: the combined booster multiplier for an activity, capped by max-total-multiplier.
     * Boosters for "all" never apply to "selling"; selling boosters must name "selling" explicitly.
     */
    public BigDecimal multiplier(UUID player, String activity) {
        List<BigDecimal> values = applicable(player, activity).stream().map(BoosterInfo::multiplier).toList();
        BigDecimal combined = rules.combine().combine(values);
        return combined.min(rules.maxTotalMultiplier()).max(BigDecimal.ONE);
    }

    public record Change(Type type, BoosterInfo booster) {
        public enum Type { ACTIVATED, EXPIRED }
    }
}
