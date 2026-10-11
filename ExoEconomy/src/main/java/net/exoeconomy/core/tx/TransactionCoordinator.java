package net.exoeconomy.core.tx;

import net.exoeconomy.core.Money;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs every player-initiated Shards movement through the same journaled sequence:
 *
 * <ol>
 *   <li>Server thread: refuse if the player already has a transaction in flight (per-player lock),
 *       storage is unhealthy or the economy is unavailable.</li>
 *   <li>Storage thread: durably insert a PENDING record (plus the operation's own rows, e.g. the
 *       sell escrow snapshot) in one SQLite transaction.</li>
 *   <li>Server thread: re-validate (player still online, menu still open, offer still valid), then
 *       call ExcellentEconomy synchronously and classify the result ({@link LedgerResult}).</li>
 *   <li>Storage thread: write the final state plus the operation's rows (e.g. the activated
 *       booster) in one SQLite transaction.</li>
 * </ol>
 *
 * <p>ExcellentEconomy and ExoEconomy's database do not share a transaction. Crash windows:
 * between steps 2 and 3 nothing has moved but the record says PENDING; between steps 3 and 4 the
 * money moved but the record says PENDING. At startup every PENDING record becomes UNCERTAIN and
 * is never retried automatically; an admin reconciles it with /exoeconomy tx. On a clean shutdown,
 * transactions still before step 3 are marked ABORTED because the economy was never called.
 */
public final class TransactionCoordinator {
    public interface Operation {
        UUID player();
        TxKind kind();
        BigDecimal amount();
        default String parent() { return null; }
        String details();
        /** Rows written atomically with the PENDING record. */
        default List<Journal.Work> beginWork(String txId) { return List.of(); }
        /** Server thread, after PENDING is durable. Return null to proceed or a message key to abort. */
        String validate();
        /** Server thread, immediately before the economy call. */
        default void beforeLedger() {}
        /** Economy succeeded. Returns rows written with COMMITTED. Throwing triggers an automatic refund of a charge. */
        List<Journal.Work> committed(String txId, LedgerResult result);
        /** Economy refused and nothing moved; undo {@link #beforeLedger()}. */
        List<Journal.Work> rejected(String txId, String reason);
        /** Outcome unknown; keep whatever is needed for reconciliation. */
        List<Journal.Work> uncertain(String txId, LedgerResult result);
        /** Abandoned before the economy call. Nothing moved. */
        void aborted(String reason);
    }

    private enum Phase { JOURNALING, LEDGER, FINISHING }

    private final Journal journal;
    private final ShardsLedger ledger;
    private final Executor mainThread;
    private final LongSupplier clock;
    private final Supplier<String> ids;
    private final Logger log;
    private final Set<UUID> busy = new HashSet<>();
    private final Map<String, Phase> inFlight = new LinkedHashMap<>();
    private volatile boolean closing;

    public TransactionCoordinator(Journal journal, ShardsLedger ledger, Executor mainThread, LongSupplier clock,
                                  Supplier<String> ids, Logger log) {
        this.journal = journal; this.ledger = ledger; this.mainThread = mainThread; this.clock = clock; this.ids = ids; this.log = log;
    }

    public boolean busy(UUID player) { return busy.contains(player); }

    public int inFlight() { return inFlight.size(); }

    /**
     * Starts an operation. Must be called on the server thread. Returns the transaction id, or null
     * when refused immediately (in which case {@link Operation#aborted} has been called).
     */
    public String submit(Operation op) {
        if (closing) { op.aborted("storage-unavailable"); return null; }
        if (!journal.healthy()) { op.aborted("storage-unavailable"); return null; }
        if (!ledger.available()) { op.aborted("economy-unavailable"); return null; }
        if (!Money.isPositiveOperation(op.amount())) { op.aborted("invalid-amount"); return null; }
        if (!busy.add(op.player())) { op.aborted("transaction-in-progress"); return null; }
        String id = ids.get();
        TxRecord record = TxRecord.pending(id, op.player(), op.kind(), op.amount(), ledger.currencyId(), op.parent(), op.details(), clock.getAsLong());
        List<Journal.Work> work;
        try {
            work = op.beginWork(id);
        } catch (RuntimeException e) {
            busy.remove(op.player());
            log.log(Level.SEVERE, "Could not prepare transaction " + id, e);
            op.aborted("internal-error");
            return null;
        }
        inFlight.put(id, Phase.JOURNALING);
        journal.begin(record, work).whenComplete((ok, error) -> mainThread.execute(() -> afterBegin(op, id, error)));
        return id;
    }

    private void afterBegin(Operation op, String id, Throwable error) {
        if (error != null) {
            inFlight.remove(id);
            busy.remove(op.player());
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (cause instanceof Journal.DuplicateTransactionException) op.aborted("transaction-duplicate");
            else { log.log(Level.SEVERE, "Could not journal transaction " + id + "; nothing was charged or paid", cause); op.aborted("storage-unavailable"); }
            return;
        }
        String invalid;
        try {
            invalid = closing ? "storage-unavailable" : op.validate();
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Validation failed for transaction " + id, e);
            invalid = "internal-error";
        }
        if (invalid != null) {
            finish(op, id, TxState.ABORTED, null, null, invalid, List.of());
            op.aborted(invalid);
            return;
        }
        inFlight.put(id, Phase.LEDGER);
        LedgerResult result;
        try {
            op.beforeLedger();
            result = op.kind().direction == TxKind.Direction.CHARGE
                    ? ledger.withdraw(op.player(), op.amount())
                    : ledger.deposit(op.player(), op.amount());
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Economy call failed unexpectedly for transaction " + id, e);
            result = new LedgerResult(LedgerResult.Outcome.UNCERTAIN, false, null, null, "exception: " + e);
        }
        final LedgerResult outcome = result;
        switch (outcome.outcome()) {
            case SUCCESS -> {
                List<Journal.Work> work;
                try {
                    work = op.committed(id, result);
                } catch (RuntimeException e) {
                    log.log(Level.SEVERE, "Transaction " + id + " was paid but could not be completed", e);
                    String note = "post-payment failure: " + e.getMessage();
                    finish(op, id, TxState.COMMITTED, result.before(), result.after(), note, List.of());
                    if (op.kind().direction == TxKind.Direction.CHARGE)
                        credit(TxKind.REFUND, id, op.player(), op.amount(), "auto=true\nreason=" + e.getMessage(), (state, why) ->
                                log.info("Automatic refund for " + id + ": " + state + (why == null ? "" : " (" + why + ")")));
                    return;
                }
                String note = result.verified() ? null : "balance change did not match exactly; review";
                finish(op, id, TxState.COMMITTED, result.before(), result.after(), note, work);
            }
            case REJECTED -> {
                List<Journal.Work> work = safe(() -> op.rejected(id, outcome.reason()), id);
                finish(op, id, TxState.FAILED, outcome.before(), outcome.after(), outcome.reason(), work);
            }
            case UNCERTAIN -> {
                log.warning("Transaction " + id + " (" + op.kind() + ", " + op.amount() + " for " + op.player()
                        + ") has an uncertain outcome: " + result.reason() + ". Reconcile with /exoeconomy tx inspect " + id);
                List<Journal.Work> work = safe(() -> op.uncertain(id, outcome), id);
                finish(op, id, TxState.UNCERTAIN, result.before(), result.after(), result.reason(), work);
            }
        }
    }

    private List<Journal.Work> safe(Supplier<List<Journal.Work>> action, String id) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Handler failed for transaction " + id, e);
            return List.of();
        }
    }

    private void finish(Operation op, String id, TxState state, BigDecimal before, BigDecimal after, String note, List<Journal.Work> work) {
        inFlight.put(id, Phase.FINISHING);
        busy.remove(op.player());
        journal.finish(id, state, before, after, note, work).whenComplete((ok, error) -> mainThread.execute(() -> {
            inFlight.remove(id);
            if (error != null)
                log.log(Level.SEVERE, "Could not record final state " + state + " for transaction " + id
                        + "; it will be flagged UNCERTAIN at next start", error);
        }));
    }

    /**
     * Pays Shards for a parent transaction (refund of a charge, or admin payout for an uncertain sale).
     * The journal's unique (parent, kind) constraint guarantees at most one non-failed child, so a
     * repeated request cannot pay twice. Works for offline players.
     *
     * @param done receives the final state (or null when refused) and a reason
     */
    public void credit(TxKind kind, String parent, UUID player, BigDecimal amount, String details, BiConsumer<TxState, String> done) {
        if (kind.direction != TxKind.Direction.PAYOUT || parent == null) throw new IllegalArgumentException("credit needs a payout kind and a parent");
        if (!journal.healthy()) { done.accept(null, "storage-unavailable"); return; }
        if (!ledger.available()) { done.accept(null, "economy-unavailable"); return; }
        if (!Money.isPositiveOperation(amount)) { done.accept(null, "invalid-amount"); return; }
        String id = ids.get();
        TxRecord record = TxRecord.pending(id, player, kind, amount, ledger.currencyId(), parent, details, clock.getAsLong());
        inFlight.put(id, Phase.JOURNALING);
        journal.begin(record, List.of()).whenComplete((ok, error) -> mainThread.execute(() -> {
            if (error != null) {
                inFlight.remove(id);
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                if (cause instanceof Journal.DuplicateTransactionException) done.accept(null, "already-processed");
                else { log.log(Level.SEVERE, "Could not journal " + kind + " for " + parent, cause); done.accept(null, "storage-unavailable"); }
                return;
            }
            if (closing) {
                journal.finish(id, TxState.ABORTED, null, null, "shutdown", List.of());
                inFlight.remove(id);
                done.accept(TxState.ABORTED, "shutdown");
                return;
            }
            inFlight.put(id, Phase.LEDGER);
            ledger.depositAnywhere(player, amount).whenComplete((result, failure) -> mainThread.execute(() -> {
                LedgerResult r = failure != null
                        ? new LedgerResult(LedgerResult.Outcome.UNCERTAIN, false, null, null, "exception: " + failure)
                        : result;
                TxState state = switch (r.outcome()) {
                    case SUCCESS -> TxState.COMMITTED;
                    case REJECTED -> TxState.FAILED;
                    case UNCERTAIN -> TxState.UNCERTAIN;
                };
                if (state == TxState.UNCERTAIN)
                    log.warning(kind + " " + id + " for parent " + parent + " has an uncertain outcome: " + r.reason());
                inFlight.put(id, Phase.FINISHING);
                journal.finish(id, state, r.before(), r.after(), r.reason(), List.of())
                        .whenComplete((v, e) -> mainThread.execute(() -> inFlight.remove(id)));
                done.accept(state, r.reason());
            }));
        }));
    }

    /**
     * Clean shutdown: refuse new work and return ids still before the economy call (safe to
     * mark ABORTED) and ids whose economy call was in progress (left PENDING, flagged at next start).
     */
    public Map<String, Boolean> close() {
        closing = true;
        Map<String, Boolean> out = new LinkedHashMap<>();
        inFlight.forEach((id, phase) -> out.put(id, phase == Phase.JOURNALING));
        return out;
    }
}
