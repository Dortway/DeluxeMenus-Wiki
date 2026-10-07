package dev.exoquests.core.points;

import dev.exoquests.core.storage.AuditStore;
import dev.exoquests.core.storage.Database;
import dev.exoquests.core.storage.PointsStore;
import dev.exoquests.core.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Balance queries and audited administrator adjustments. Works for online and offline players by UUID. */
public final class PointsService {

    public enum Op { GIVE, TAKE, SET }

    private final Database db;
    private final LongSupplier maxBalance;
    private final Clock clock;

    public PointsService(Database db, LongSupplier maxBalance, Clock clock) {
        this.db = db;
        this.maxBalance = maxBalance;
        this.clock = clock;
    }

    public CompletableFuture<Long> balance(UUID player) {
        return db.submit(c -> PointsStore.balance(c, player));
    }

    /** Validates an admin amount: whole number, 0..maxBalance (give/take need at least 1). */
    public boolean validAmount(Op op, long amount) {
        long min = op == Op.SET ? 0 : 1;
        return amount >= min && amount <= maxBalance.getAsLong();
    }

    public CompletableFuture<PointsStore.Change> adjust(Op op, UUID player, long amount, String actor) {
        if (!validAmount(op, amount)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("amount out of range"));
        }
        long now = clock.now().toEpochMilli();
        long max = maxBalance.getAsLong();
        String ref = "admin:" + UUID.randomUUID();
        return db.submit(c -> {
            PointsStore.Change change = switch (op) {
                case GIVE -> PointsStore.credit(c, player, amount, max, "admin:give", ref, actor, now);
                case TAKE -> PointsStore.debit(c, player, amount, "admin:take", ref, actor, now);
                case SET -> PointsStore.set(c, player, amount, max, "admin:set", ref, actor, now);
            };
            if (change.applied()) {
                AuditStore.record(c, actor, "points." + op.name().toLowerCase(java.util.Locale.ROOT),
                        player.toString(), "amount=" + amount + " delta=" + change.delta()
                                + " balance=" + change.balance(), now);
            }
            return change;
        });
    }

    public CompletableFuture<Void> audit(String actor, String action, String target, String details) {
        long now = clock.now().toEpochMilli();
        return db.submit(c -> {
            AuditStore.record(c, actor, action, target, details, now);
            return null;
        });
    }
}
