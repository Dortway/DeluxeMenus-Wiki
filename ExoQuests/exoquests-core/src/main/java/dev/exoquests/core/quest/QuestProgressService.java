package dev.exoquests.core.quest;

import dev.exoquests.core.storage.AssignmentRow;
import dev.exoquests.core.storage.CompletionResult;
import dev.exoquests.core.storage.Database;
import dev.exoquests.core.storage.QuestStore;
import dev.exoquests.core.time.Clock;
import dev.exoquests.core.time.ResetSchedule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Tracks daily quest progress for online players.
 *
 * <p><b>Threading:</b> every public method must be called on the server main thread (the {@code main}
 * executor). Database work runs on the database thread and its results are handed back to {@code main}.
 * This confinement means no locking is needed for session state.</p>
 *
 * <p><b>Exactly-once rewards:</b> progress is accumulated in memory and flushed periodically; the
 * transition to "completed" and the point credit happen together in a single conditional database
 * transaction ({@link QuestStore#complete}). A crash can lose at most the unflushed progress of the last
 * flush interval, never duplicate a reward.</p>
 *
 * <p><b>Resets:</b> the current period is derived from the clock on every access. When it changes, all
 * sessions flush their old progress (which simply stays attached to the expired period) and load or
 * create the new period's assignment. Actions captured for an expired period are discarded.</p>
 */
public final class QuestProgressService {

    private static final int MAX_PENDING_AMOUNT = 1_000_000;

    public interface Listener {
        /** Assignment (re)loaded; {@code reset} is true after a daily or admin reset. */
        void onLoaded(UUID player, boolean reset);

        /** A slot completed and was credited exactly once. {@code definition} is empty if since removed. */
        void onCompleted(UUID player, Optional<QuestDefinition> definition, CompletionResult result, boolean online);

        void onError(String message, Throwable error);
    }

    public record SlotView(int slot, String questId, int target, int reward, int progress, boolean completed) {
    }

    public record SessionView(String period, boolean loading, boolean failed, List<SlotView> slots) {
    }

    private static final class Slot {
        final int index;
        final String questId;
        final int target;
        final int reward;
        int progress;
        int persisted;
        boolean completed;
        boolean completing;

        Slot(AssignmentRow row) {
            this.index = row.slot();
            this.questId = row.questId();
            this.target = row.target();
            this.reward = row.reward();
            this.progress = row.completed() ? row.target() : row.progress();
            this.persisted = this.progress;
            this.completed = row.completed();
        }
    }

    private static final class Session {
        final UUID uuid;
        String period;
        boolean loading;
        boolean failed;
        int generation;
        List<Slot> slots = List.of();
        final Map<String, QuestAction> pending = new LinkedHashMap<>();

        Session(UUID uuid) {
            this.uuid = uuid;
        }
    }

    private final Database db;
    private final Executor main;
    private final Clock clock;
    private final Supplier<QuestPool> pool;
    private final LongSupplier maxBalance;
    private final Supplier<Random> random;
    private final Listener listener;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private ResetSchedule schedule;
    private String currentPeriod;
    private Instant nextReset;

    public QuestProgressService(Database db, Executor main, Clock clock, ResetSchedule schedule,
                                Supplier<QuestPool> pool, LongSupplier maxBalance, Supplier<Random> random,
                                Listener listener) {
        this.db = Objects.requireNonNull(db);
        this.main = Objects.requireNonNull(main);
        this.clock = Objects.requireNonNull(clock);
        this.schedule = Objects.requireNonNull(schedule);
        this.pool = Objects.requireNonNull(pool);
        this.maxBalance = Objects.requireNonNull(maxBalance);
        this.random = Objects.requireNonNull(random);
        this.listener = Objects.requireNonNull(listener);
    }

    // ------------------------------------------------------------------ period handling

    /** The active period key; triggers a rollover if a reset boundary has passed. */
    public String currentPeriod() {
        Instant now = clock.now();
        if (nextReset == null || !now.isBefore(nextReset)) {
            String period = schedule.periodKey(now);
            nextReset = schedule.nextReset(now);
            if (!period.equals(currentPeriod)) {
                String previous = currentPeriod;
                currentPeriod = period;
                if (previous != null) {
                    rollover();
                }
            }
        }
        return currentPeriod;
    }

    public Duration timeUntilReset() {
        currentPeriod();
        return Duration.between(clock.now(), nextReset);
    }

    /** Applies a new reset time/timezone (config reload). May start a new period immediately. */
    public void updateSchedule(ResetSchedule newSchedule) {
        this.schedule = Objects.requireNonNull(newSchedule);
        this.nextReset = null;
        currentPeriod();
    }

    private void rollover() {
        for (Session s : sessions.values()) {
            flushSession(s, new ArrayList<>());
            startLoad(s, currentPeriod, true);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public void join(UUID uuid) {
        String period = currentPeriod();
        if (sessions.containsKey(uuid)) {
            return;
        }
        Session s = new Session(uuid);
        sessions.put(uuid, s);
        startLoad(s, period, false);
    }

    /** Retries a failed load (for example after a transient database error). */
    public void ensureLoaded(UUID uuid) {
        Session s = sessions.get(uuid);
        if (s != null && s.failed) {
            startLoad(s, currentPeriod(), false);
        }
    }

    public CompletableFuture<Void> quit(UUID uuid) {
        Session s = sessions.remove(uuid);
        if (s == null) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<?>> work = new ArrayList<>();
        flushSession(s, work);
        // Actions buffered while the assignment was still loading are applied directly in the database.
        for (QuestAction pending : s.pending.values()) {
            work.add(offline(uuid, pending, s.period));
        }
        s.pending.clear();
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }

    public boolean isTracked(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    private void startLoad(Session s, String period, boolean reset) {
        s.period = period;
        s.loading = true;
        s.failed = false;
        s.slots = List.of();
        int generation = ++s.generation;
        QuestPool snapshot = pool.get();
        Random rnd = random.get();
        long now = clock.now().toEpochMilli();
        db.submit(c -> QuestStore.loadOrAssign(c, s.uuid, period, snapshot, rnd, now))
                .whenCompleteAsync((rows, error) -> {
                    if (sessions.get(s.uuid) != s || s.generation != generation) {
                        return;
                    }
                    s.loading = false;
                    if (error != null) {
                        s.failed = true;
                        listener.onError("could not load quests for " + s.uuid, error);
                        return;
                    }
                    List<Slot> slots = new ArrayList<>(rows.size());
                    for (AssignmentRow row : rows) {
                        slots.add(new Slot(row));
                    }
                    s.slots = slots;
                    List<QuestAction> buffered = new ArrayList<>(s.pending.values());
                    s.pending.clear();
                    for (QuestAction a : buffered) {
                        apply(s, a);
                    }
                    listener.onLoaded(s.uuid, reset);
                }, main);
    }

    // ------------------------------------------------------------------ progress

    /**
     * Records a qualifying action.
     *
     * @param eventPeriod   the value of {@link #currentPeriod()} when the action happened
     * @param creditOffline when the player is not online, apply the action directly in the database
     */
    public void record(UUID uuid, QuestAction action, String eventPeriod, boolean creditOffline) {
        String period = currentPeriod();
        if (!period.equals(eventPeriod)) {
            return;
        }
        Session s = sessions.get(uuid);
        if (s == null) {
            if (creditOffline) {
                offline(uuid, action, period);
            }
            return;
        }
        if (s.loading || s.failed) {
            String key = action.type() + ":" + action.key() + ":" + action.natural();
            s.pending.merge(key, action, (a, b) -> new QuestAction(a.type(), a.key(),
                    (int) Math.min(MAX_PENDING_AMOUNT, (long) a.amount() + b.amount()), a.natural()));
            return;
        }
        apply(s, action);
    }

    /** True if the player currently has an unfinished quest that this action would advance. */
    public boolean wants(UUID uuid, QuestType type, String key) {
        Session s = sessions.get(uuid);
        if (s == null) {
            return false;
        }
        if (s.loading || s.failed) {
            return true;
        }
        QuestPool p = pool.get();
        for (Slot slot : s.slots) {
            if (slot.completed || slot.completing) {
                continue;
            }
            Optional<QuestDefinition> def = p.get(slot.questId);
            if (def.isPresent() && def.get().type() == type
                    && (def.get().keys().isEmpty() || def.get().keys().contains(key))) {
                return true;
            }
        }
        return false;
    }

    /** True if the player has any unfinished quest of this type (used to skip expensive detection). */
    public boolean wantsType(UUID uuid, QuestType type) {
        Session s = sessions.get(uuid);
        if (s == null) {
            return false;
        }
        if (s.loading || s.failed) {
            return true;
        }
        QuestPool p = pool.get();
        for (Slot slot : s.slots) {
            if (!slot.completed && !slot.completing
                    && p.get(slot.questId).map(d -> d.type() == type).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private void apply(Session s, QuestAction action) {
        QuestPool p = pool.get();
        for (Slot slot : s.slots) {
            if (slot.completed || slot.completing) {
                continue;
            }
            Optional<QuestDefinition> def = p.get(slot.questId);
            if (def.isEmpty() || !def.get().matches(action)) {
                continue;
            }
            slot.progress = (int) Math.min(slot.target, (long) slot.progress + action.amount());
            if (slot.progress >= slot.target) {
                complete(s, slot);
            }
        }
    }

    private void complete(Session s, Slot slot) {
        slot.completing = true;
        String period = s.period;
        long max = maxBalance.getAsLong();
        long now = clock.now().toEpochMilli();
        db.submit(c -> QuestStore.complete(c, s.uuid, period, slot.index, max, now))
                .whenCompleteAsync((result, error) -> {
                    slot.completing = false;
                    if (error != null) {
                        // Left incomplete; the next flush retries the completion.
                        listener.onError("could not complete quest " + slot.questId + " for " + s.uuid, error);
                        return;
                    }
                    slot.completed = true;
                    slot.progress = slot.target;
                    slot.persisted = slot.target;
                    if (result.credited()) {
                        boolean online = sessions.get(s.uuid) == s;
                        listener.onCompleted(s.uuid, pool.get().get(slot.questId), result, online);
                    }
                }, main);
    }

    private CompletableFuture<?> offline(UUID uuid, QuestAction action, String period) {
        QuestPool snapshot = pool.get();
        Random rnd = random.get();
        long max = maxBalance.getAsLong();
        long now = clock.now().toEpochMilli();
        return db.submit(c -> QuestStore.applyOffline(c, uuid, period, action, snapshot, rnd, max, now))
                .whenCompleteAsync((results, error) -> {
                    if (error != null) {
                        listener.onError("could not apply offline progress for " + uuid, error);
                        return;
                    }
                    for (CompletionResult r : results) {
                        if (r.credited()) {
                            listener.onCompleted(uuid, pool.get().get(r.questId()), r, false);
                        }
                    }
                }, main);
    }

    // ------------------------------------------------------------------ persistence

    /** Writes all unsaved progress and retries completions that previously failed. */
    public CompletableFuture<Void> flushAll() {
        currentPeriod();
        List<CompletableFuture<?>> work = new ArrayList<>();
        for (Session s : sessions.values()) {
            flushSession(s, work);
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }

    private void flushSession(Session s, List<CompletableFuture<?>> work) {
        if (s.loading || s.failed) {
            return;
        }
        List<int[]> updates = new ArrayList<>();
        List<Slot> dirty = new ArrayList<>();
        for (Slot slot : s.slots) {
            if (slot.completed || slot.completing) {
                continue;
            }
            if (slot.progress >= slot.target) {
                complete(s, slot);
                continue;
            }
            if (slot.progress > slot.persisted) {
                updates.add(new int[]{slot.index, slot.progress});
                dirty.add(slot);
            }
        }
        if (updates.isEmpty()) {
            return;
        }
        String period = s.period;
        work.add(db.submit(c -> {
            for (int[] u : updates) {
                QuestStore.saveProgress(c, s.uuid, period, u[0], u[1]);
            }
            return null;
        }).whenCompleteAsync((ok, error) -> {
            if (error != null) {
                listener.onError("could not save quest progress for " + s.uuid, error);
                return;
            }
            for (int i = 0; i < dirty.size(); i++) {
                Slot slot = dirty.get(i);
                slot.persisted = Math.max(slot.persisted, updates.get(i)[1]);
            }
        }, main));
    }

    // ------------------------------------------------------------------ admin

    /** Discards the player's assignment for the current period; a fresh one is created on next load. */
    public CompletableFuture<Integer> adminReset(UUID uuid) {
        String period = currentPeriod();
        Session s = sessions.get(uuid);
        if (s != null) {
            s.generation++;
            s.loading = true;
            s.slots = List.of();
        }
        CompletableFuture<Integer> result = new CompletableFuture<>();
        db.submit(c -> QuestStore.reset(c, uuid, period)).whenCompleteAsync((count, error) -> {
            Session current = sessions.get(uuid);
            if (current != null) {
                startLoad(current, currentPeriod(), true);
            }
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(count);
            }
        }, main);
        return result;
    }

    // ------------------------------------------------------------------ views

    public Optional<SessionView> view(UUID uuid) {
        currentPeriod();
        Session s = sessions.get(uuid);
        if (s == null) {
            return Optional.empty();
        }
        List<SlotView> slots = new ArrayList<>(s.slots.size());
        for (Slot slot : s.slots) {
            slots.add(new SlotView(slot.index, slot.questId, slot.target, slot.reward, slot.progress,
                    slot.completed));
        }
        return Optional.of(new SessionView(s.period, s.loading, s.failed, List.copyOf(slots)));
    }
}
