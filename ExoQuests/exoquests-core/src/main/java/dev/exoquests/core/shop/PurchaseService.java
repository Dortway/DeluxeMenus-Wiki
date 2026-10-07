package dev.exoquests.core.shop;

import dev.exoquests.core.storage.Database;
import dev.exoquests.core.storage.PointsStore;
import dev.exoquests.core.storage.PurchaseRecord;
import dev.exoquests.core.storage.PurchaseState;
import dev.exoquests.core.storage.PurchaseStore;
import dev.exoquests.core.storage.AuditStore;
import dev.exoquests.core.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes shop purchases as a sequence of durable steps:
 *
 * <ol>
 *   <li>main thread: re-validate price, revision, permission and inventory space against current config;</li>
 *   <li>database: conditional debit + purchase record in state PENDING (one transaction);</li>
 *   <li>main thread: re-check space (refund if it no longer fits);</li>
 *   <li>database: PENDING → DELIVERING (committed before anything is handed out);</li>
 *   <li>main thread: deliver;</li>
 *   <li>database: DELIVERING → DELIVERED.</li>
 * </ol>
 *
 * Debiting and delivering cannot be one atomic operation. If the server stops after step 2 the record
 * is PENDING (safe to deliver later or refund). If it stops during steps 4–6 the record is DELIVERING and
 * is flagged NEEDS_REVIEW at startup — it is never replayed automatically, so commands never run twice.
 *
 * <p>Only one purchase per player runs at a time; concurrent attempts are rejected with BUSY.</p>
 */
public final class PurchaseService {

    private final Database db;
    private final DeliveryPort port;
    private final Supplier<ShopCatalog> catalog;
    private final LongSupplier maxBalance;
    private final Clock clock;
    private final Logger logger;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PurchaseService(Database db, DeliveryPort port, Supplier<ShopCatalog> catalog, LongSupplier maxBalance,
                           Clock clock, Logger logger) {
        this.db = db;
        this.port = port;
        this.catalog = catalog;
        this.maxBalance = maxBalance;
        this.clock = clock;
        this.logger = logger;
    }

    public boolean isBusy(UUID player) {
        return inFlight.contains(player);
    }

    private record Prepared(ShopEntry entry, String snapshot) {
    }

    /**
     * Purchases {@code itemId} if its current price and revision still equal what the player confirmed.
     */
    public CompletableFuture<PurchaseResult> purchase(UUID player, String itemId, int expectedPrice,
                                                      String expectedRevision) {
        if (!inFlight.add(player)) {
            return CompletableFuture.completedFuture(PurchaseResult.of(PurchaseResult.Status.BUSY));
        }
        CompletableFuture<PurchaseResult> result = new CompletableFuture<>();
        try {
            CompletableFuture.supplyAsync(() -> validate(player, itemId, expectedPrice, expectedRevision), port.mainThread())
                    .thenCompose(v -> v.result != null ? CompletableFuture.completedFuture(v.result)
                            : debitAndDeliver(player, v.prepared))
                    .whenComplete((r, error) -> {
                        inFlight.remove(player);
                        if (error != null) {
                            logger.log(Level.SEVERE, "purchase of " + itemId + " by " + player + " failed", unwrap(error));
                            result.complete(PurchaseResult.of(PurchaseResult.Status.ERROR));
                        } else {
                            result.complete(r);
                        }
                    });
        } catch (RuntimeException e) {
            inFlight.remove(player);
            result.complete(PurchaseResult.of(PurchaseResult.Status.ERROR));
        }
        return result;
    }

    private record Validation(PurchaseResult result, Prepared prepared) {
    }

    private Validation validate(UUID player, String itemId, int expectedPrice, String expectedRevision) {
        Optional<ShopEntry> current = catalog.get().get(itemId);
        if (current.isEmpty() || !current.get().enabled()) {
            return new Validation(PurchaseResult.of(PurchaseResult.Status.UNAVAILABLE), null);
        }
        ShopEntry entry = current.get();
        if (entry.price() != expectedPrice) {
            return new Validation(PurchaseResult.of(PurchaseResult.Status.PRICE_CHANGED), null);
        }
        if (!entry.revision().equals(expectedRevision)) {
            return new Validation(PurchaseResult.of(PurchaseResult.Status.REWARD_CHANGED), null);
        }
        if (!PriceRules.valid(entry.price())) {
            return new Validation(PurchaseResult.of(PurchaseResult.Status.UNAVAILABLE), null);
        }
        PurchaseResult.Status check = map(port.precheck(player, entry));
        if (check != null) {
            return new Validation(PurchaseResult.of(check), null);
        }
        return new Validation(null, new Prepared(entry, port.snapshot(entry)));
    }

    private CompletableFuture<PurchaseResult> debitAndDeliver(UUID player, Prepared p) {
        String purchaseId = UUID.randomUUID().toString();
        ShopEntry e = p.entry();
        long now = clock.now().toEpochMilli();
        PurchaseRecord record = new PurchaseRecord(purchaseId, player, e.id(), e.price(), e.revision(),
                e.type().name(), p.snapshot(), PurchaseState.PENDING, null, now, now);
        return db.submit(c -> {
            PointsStore.Change debit = PointsStore.debit(c, player, e.price(), "purchase:" + e.id(),
                    "purchase:" + purchaseId, player.toString(), now);
            if (!debit.applied()) {
                return debit;
            }
            PurchaseStore.insert(c, record);
            return debit;
        }).thenCompose(debit -> {
            if (!debit.applied()) {
                return CompletableFuture.completedFuture(
                        new PurchaseResult(PurchaseResult.Status.INSUFFICIENT_FUNDS, debit.balance(), null, e.id()));
            }
            return deliverPending(record, debit.balance());
        });
    }

    /**
     * Delivers a PENDING purchase: re-checks on the main thread, marks DELIVERING, delivers, marks DELIVERED.
     */
    private CompletableFuture<PurchaseResult> deliverPending(PurchaseRecord record, long balanceAfterDebit) {
        RewardType type = RewardType.valueOf(record.rewardType());
        return CompletableFuture.supplyAsync(() -> port.precheckSnapshot(record.player(), type, record.rewardSnapshot()),
                port.mainThread()).thenCompose(check -> {
            if (check == DeliveryPort.Check.OFFLINE) {
                return CompletableFuture.completedFuture(
                        new PurchaseResult(PurchaseResult.Status.QUEUED, balanceAfterDebit, record.purchaseId(), record.itemId()));
            }
            if (check != DeliveryPort.Check.OK) {
                return refund(record.purchaseId(), "not deliverable: " + check)
                        .thenApply(balance -> new PurchaseResult(PurchaseResult.Status.REFUNDED, balance,
                                record.purchaseId(), record.itemId()));
            }
            long now = clock.now().toEpochMilli();
            return db.submit(c -> PurchaseStore.transition(c, record.purchaseId(), PurchaseState.PENDING,
                    PurchaseState.DELIVERING, null, now)).thenCompose(moved -> {
                if (!moved) {
                    // Someone else (an admin or a parallel resume) already handled this record.
                    return CompletableFuture.completedFuture(
                            new PurchaseResult(PurchaseResult.Status.ERROR, balanceAfterDebit, record.purchaseId(), record.itemId()));
                }
                return CompletableFuture.supplyAsync(() -> {
                    // Re-check on the same tick as delivery: nothing can change the inventory in between.
                    DeliveryPort.Check again = port.precheckSnapshot(record.player(), type, record.rewardSnapshot());
                    if (again != DeliveryPort.Check.OK) {
                        return again;
                    }
                    try {
                        port.deliver(record.player(), record.purchaseId(), record.itemId(), record.price(), type,
                                record.rewardSnapshot());
                        return DeliveryPort.Check.OK;
                    } catch (Exception ex) {
                        logger.log(Level.SEVERE, "delivery of purchase " + record.purchaseId() + " failed", ex);
                        return null;
                    }
                }, port.mainThread()).thenCompose(outcome -> finish(record, outcome, balanceAfterDebit));
            });
        });
    }

    private CompletableFuture<PurchaseResult> finish(PurchaseRecord record, DeliveryPort.Check outcome,
                                                     long balance) {
        long now = clock.now().toEpochMilli();
        if (outcome == DeliveryPort.Check.OK) {
            return db.submit(c -> PurchaseStore.transition(c, record.purchaseId(), PurchaseState.DELIVERING,
                            PurchaseState.DELIVERED, null, now))
                    .thenApply(ok -> new PurchaseResult(PurchaseResult.Status.SUCCESS, balance, record.purchaseId(), record.itemId()));
        }
        if (outcome == DeliveryPort.Check.OFFLINE) {
            // Player left between the two checks; nothing was handed out. Keep it for their next join.
            return db.submit(c -> PurchaseStore.transition(c, record.purchaseId(), PurchaseState.DELIVERING,
                            PurchaseState.PENDING, "player went offline before delivery", now))
                    .thenApply(ok -> new PurchaseResult(PurchaseResult.Status.QUEUED, balance, record.purchaseId(), record.itemId()));
        }
        if (outcome != null) {
            // Nothing was handed out (pre-delivery check failed on the delivery tick): safe to refund.
            return db.submit(c -> {
                PurchaseStore.transition(c, record.purchaseId(), PurchaseState.DELIVERING, PurchaseState.PENDING,
                        "re-check failed: " + outcome, now);
                return PurchaseStore.refund(c, record.purchaseId(), PurchaseState.PENDING, maxBalance.getAsLong(),
                        "system", "re-check failed: " + outcome, now);
            }).thenApply(change -> new PurchaseResult(PurchaseResult.Status.REFUNDED,
                    change.map(PointsStore.Change::balance).orElse(balance), record.purchaseId(), record.itemId()));
        }
        return db.submit(c -> PurchaseStore.transition(c, record.purchaseId(), PurchaseState.DELIVERING,
                        PurchaseState.NEEDS_REVIEW, "delivery threw an exception", now))
                .thenApply(ok -> new PurchaseResult(PurchaseResult.Status.NEEDS_REVIEW, balance, record.purchaseId(), record.itemId()));
    }

    private CompletableFuture<Long> refund(String purchaseId, String note) {
        long now = clock.now().toEpochMilli();
        return db.submit(c -> PurchaseStore.refund(c, purchaseId, PurchaseState.PENDING, maxBalance.getAsLong(),
                        "system", note, now))
                .thenApply(change -> change.map(PointsStore.Change::balance).orElse(-1L));
    }

    /**
     * Retries PENDING purchases for a player who just joined. Each is delivered, or refunded if it does
     * not fit. Returns the results in order.
     */
    public CompletableFuture<List<PurchaseResult>> resumePending(UUID player) {
        if (!inFlight.add(player)) {
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<PurchaseResult>> out = new CompletableFuture<>();
        db.submit(c -> PurchaseStore.byPlayerAndState(c, player, PurchaseState.PENDING))
                .thenCompose(records -> {
                    CompletableFuture<List<PurchaseResult>> chain = CompletableFuture.completedFuture(new java.util.ArrayList<>());
                    for (PurchaseRecord r : records) {
                        chain = chain.thenCompose(list -> {
                            if (!list.isEmpty() && list.get(list.size() - 1).status() == PurchaseResult.Status.QUEUED) {
                                return CompletableFuture.completedFuture(list);
                            }
                            return deliverPending(r, -1).thenApply(res -> {
                                list.add(res);
                                return list;
                            });
                        });
                    }
                    return chain;
                })
                .whenComplete((list, error) -> {
                    inFlight.remove(player);
                    if (error != null) {
                        logger.log(Level.SEVERE, "resuming purchases for " + player + " failed", unwrap(error));
                        out.complete(List.of());
                    } else {
                        out.complete(List.copyOf(list));
                    }
                });
        return out;
    }

    /** Startup recovery: interrupted deliveries become NEEDS_REVIEW. Returns how many were flagged. */
    public CompletableFuture<Integer> flagInterrupted() {
        long now = clock.now().toEpochMilli();
        return db.submit(c -> PurchaseStore.flagInterrupted(c, now));
    }

    public CompletableFuture<List<PurchaseRecord>> listReview(int limit) {
        return db.submit(c -> PurchaseStore.byState(c, PurchaseState.NEEDS_REVIEW, limit));
    }

    public enum Resolution { DELIVERED, REFUND }

    /** Staff decision for a NEEDS_REVIEW purchase. Returns false if the purchase is not awaiting review. */
    public CompletableFuture<Boolean> resolve(String purchaseId, Resolution resolution, String actor) {
        long now = clock.now().toEpochMilli();
        return db.submit(c -> {
            boolean done;
            if (resolution == Resolution.DELIVERED) {
                done = PurchaseStore.transition(c, purchaseId, PurchaseState.NEEDS_REVIEW, PurchaseState.RESOLVED,
                        "confirmed delivered by " + actor, now);
            } else {
                done = PurchaseStore.refund(c, purchaseId, PurchaseState.NEEDS_REVIEW, maxBalance.getAsLong(), actor,
                        "refunded by " + actor, now).isPresent();
            }
            if (done) {
                AuditStore.record(c, actor, "purchase.resolve." + resolution.name().toLowerCase(java.util.Locale.ROOT),
                        purchaseId, null, now);
            }
            return done;
        });
    }

    private static PurchaseResult.Status map(DeliveryPort.Check check) {
        return switch (check) {
            case OK -> null;
            case OFFLINE -> PurchaseResult.Status.OFFLINE;
            case NO_PERMISSION -> PurchaseResult.Status.NO_PERMISSION;
            case NO_SPACE -> PurchaseResult.Status.NO_SPACE;
            case INVALID_NAME -> PurchaseResult.Status.INVALID_NAME;
            case UNAVAILABLE -> PurchaseResult.Status.UNAVAILABLE;
        };
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
