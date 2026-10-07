package dev.exoquests.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exoquests.core.config.ConfigBundle;
import dev.exoquests.core.shop.DeliveryPort;
import dev.exoquests.core.shop.PurchaseResult;
import dev.exoquests.core.shop.PurchaseService;
import dev.exoquests.core.shop.RewardType;
import dev.exoquests.core.shop.ShopCatalog;
import dev.exoquests.core.shop.ShopEntry;
import dev.exoquests.core.storage.Database;
import dev.exoquests.core.storage.PointsStore;
import dev.exoquests.core.storage.PurchaseRecord;
import dev.exoquests.core.storage.PurchaseState;
import dev.exoquests.core.storage.PurchaseStore;
import dev.exoquests.core.time.Clock;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PurchaseServiceTest {

    @TempDir
    Path dir;
    Database db;
    ExecutorService main;
    ConfigBundle config;
    AtomicReference<ShopCatalog> catalog;
    FakePort port;
    PurchaseService service;
    final UUID player = UUID.randomUUID();

    /** Scriptable platform: scripted checks are consumed in order, then OK. */
    static final class FakePort implements DeliveryPort {
        final Executor main;
        volatile Check precheck = Check.OK;
        final ConcurrentLinkedDeque<Check> snapshotChecks = new ConcurrentLinkedDeque<>();
        final Map<UUID, AtomicInteger> delivered = new ConcurrentHashMap<>();
        volatile boolean throwOnDeliver;

        FakePort(Executor main) {
            this.main = main;
        }

        @Override
        public Executor mainThread() {
            return main;
        }

        @Override
        public Check precheck(UUID p, ShopEntry entry) {
            return precheck;
        }

        @Override
        public Check precheckSnapshot(UUID p, RewardType type, String snapshot) {
            Check c = snapshotChecks.poll();
            return c == null ? Check.OK : c;
        }

        @Override
        public String snapshot(ShopEntry entry) {
            return entry.type() + ":" + entry.id() + ":" + entry.revision();
        }

        @Override
        public void deliver(UUID p, String purchaseId, String itemId, int price, RewardType type, String snapshot)
                throws Exception {
            if (throwOnDeliver) {
                throw new IllegalStateException("simulated delivery failure");
            }
            delivered.computeIfAbsent(p, k -> new AtomicInteger()).incrementAndGet();
        }

        int deliveredTo(UUID p) {
            AtomicInteger i = delivered.get(p);
            return i == null ? 0 : i.get();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        config = TestSupport.defaults(dir);
        db = TestSupport.open(dir);
        main = TestSupport.mainThread();
        catalog = new AtomicReference<>(config.shop());
        port = new FakePort(main);
        service = new PurchaseService(db, port, catalog::get, () -> 1_000_000_000L, Clock.SYSTEM, TestSupport.LOG);
    }

    @AfterEach
    void tearDown() {
        main.shutdownNow();
        db.close();
    }

    private void give(UUID p, long amount) throws Exception {
        db.submit(c -> PointsStore.credit(c, p, amount, 1_000_000_000L, "test", "seed:" + UUID.randomUUID(), "test", 0))
                .get();
    }

    private long balance(UUID p) throws Exception {
        return db.submit(c -> PointsStore.balance(c, p)).get();
    }

    private ShopEntry diamonds() {
        return catalog.get().get("diamonds").orElseThrow();
    }

    private PurchaseResult buy(UUID p, ShopEntry e) throws Exception {
        return service.purchase(p, e.id(), e.price(), e.revision()).get(10, TimeUnit.SECONDS);
    }

    private PurchaseRecord record(String id) throws Exception {
        return db.submit(c -> PurchaseStore.find(c, id)).get().orElseThrow();
    }

    @Test
    void successfulPurchaseChargesOnceAndDelivers() throws Exception {
        give(player, 500);
        PurchaseResult r = buy(player, diamonds());
        assertEquals(PurchaseResult.Status.SUCCESS, r.status());
        assertEquals(380, r.balance());
        assertEquals(380, balance(player));
        assertEquals(1, port.deliveredTo(player));
        assertEquals(PurchaseState.DELIVERED, record(r.purchaseId()).state());
    }

    @Test
    void insufficientFundsChargesNothing() throws Exception {
        give(player, 119);
        PurchaseResult r = buy(player, diamonds());
        assertEquals(PurchaseResult.Status.INSUFFICIENT_FUNDS, r.status());
        assertEquals(119, balance(player));
        assertEquals(0, port.deliveredTo(player));
        assertEquals(0, (int) db.submit(c -> PurchaseStore.byPlayerAndState(c, player, PurchaseState.PENDING).size()).get());
    }

    @Test
    void staleConfirmationIsRejected() throws Exception {
        give(player, 500);
        ShopEntry confirmed = diamonds();
        // Price edited by staff after the confirmation menu was opened.
        catalog.set(catalog.get().replace(confirmed.withPrice(150)));
        assertEquals(PurchaseResult.Status.PRICE_CHANGED, buy(player, confirmed).status());
        // Reward contents changed (different revision).
        ShopEntry changed = new ShopEntry(confirmed.id(), confirmed.price(), confirmed.type(), confirmed.item(),
                confirmed.commands(), confirmed.icon(), confirmed.displayName(), confirmed.displayLore(), null, true,
                "different");
        catalog.set(catalog.get().replace(changed));
        assertEquals(PurchaseResult.Status.REWARD_CHANGED, buy(player, confirmed).status());
        // Removed.
        catalog.set(catalog.get().without(confirmed.id()));
        assertEquals(PurchaseResult.Status.UNAVAILABLE, buy(player, confirmed).status());
        assertEquals(500, balance(player));
        assertEquals(0, port.deliveredTo(player));
    }

    @Test
    void noInventorySpaceChargesNothing() throws Exception {
        give(player, 500);
        port.precheck = DeliveryPort.Check.NO_SPACE;
        assertEquals(PurchaseResult.Status.NO_SPACE, buy(player, diamonds()).status());
        assertEquals(500, balance(player));
        port.precheck = DeliveryPort.Check.NO_PERMISSION;
        assertEquals(PurchaseResult.Status.NO_PERMISSION, buy(player, diamonds()).status());
        assertEquals(500, balance(player));
    }

    @Test
    void spaceLostAfterDebitIsRefunded() throws Exception {
        give(player, 500);
        port.snapshotChecks.add(DeliveryPort.Check.OK);
        port.snapshotChecks.add(DeliveryPort.Check.NO_SPACE); // the delivery-tick re-check fails
        PurchaseResult r = buy(player, diamonds());
        assertEquals(PurchaseResult.Status.REFUNDED, r.status());
        assertEquals(500, balance(player));
        assertEquals(0, port.deliveredTo(player));
        assertEquals(PurchaseState.REFUNDED, record(r.purchaseId()).state());
    }

    @Test
    void deliveryFailureGoesToReviewAndCanBeRefundedOnce() throws Exception {
        give(player, 500);
        port.throwOnDeliver = true;
        PurchaseResult r = buy(player, diamonds());
        assertEquals(PurchaseResult.Status.NEEDS_REVIEW, r.status());
        assertEquals(380, balance(player));
        assertEquals(1, service.listReview(10).get().size());
        assertTrue(service.resolve(r.purchaseId(), PurchaseService.Resolution.REFUND, "admin").get());
        assertFalse(service.resolve(r.purchaseId(), PurchaseService.Resolution.REFUND, "admin").get());
        assertFalse(service.resolve(r.purchaseId(), PurchaseService.Resolution.DELIVERED, "admin").get());
        assertEquals(500, balance(player));
    }

    @Test
    void concurrentPurchasesBySamePlayerNeverOverspend() throws Exception {
        give(player, 1_000); // enough for 8 diamond purchases
        ShopEntry e = diamonds();
        int attempts = 64;
        ExecutorService clients = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<PurchaseResult>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            CompletableFuture<PurchaseResult> f = new CompletableFuture<>();
            futures.add(f);
            clients.execute(() -> {
                try {
                    start.await();
                    f.complete(service.purchase(player, e.id(), e.price(), e.revision()).get());
                } catch (Exception ex) {
                    f.completeExceptionally(ex);
                }
            });
        }
        start.countDown();
        int success = 0;
        for (CompletableFuture<PurchaseResult> f : futures) {
            PurchaseResult.Status s = f.get(30, TimeUnit.SECONDS).status();
            assertTrue(s == PurchaseResult.Status.SUCCESS || s == PurchaseResult.Status.BUSY
                    || s == PurchaseResult.Status.INSUFFICIENT_FUNDS, s.toString());
            if (s == PurchaseResult.Status.SUCCESS) {
                success++;
            }
        }
        clients.shutdown();
        // Drain whatever is left sequentially: the total can never exceed what the balance allows.
        while (buy(player, e).status() == PurchaseResult.Status.SUCCESS) {
            success++;
        }
        assertEquals(8, success);
        assertEquals(1_000 - 8 * 120, balance(player));
        assertEquals(8, port.deliveredTo(player));
    }

    @Test
    void databaseRejectsDoubleDebitEvenWithoutTheInFlightGuard() throws Exception {
        give(player, 1_000);
        ShopEntry e = diamonds();
        // Separate service instances do not share the per-player guard, so only the database protects us.
        List<PurchaseService> services = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            services.add(new PurchaseService(db, port, catalog::get, () -> 1_000_000_000L, Clock.SYSTEM, TestSupport.LOG));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService clients = Executors.newFixedThreadPool(24);
        List<CompletableFuture<PurchaseResult>> futures = new ArrayList<>();
        for (PurchaseService s : services) {
            CompletableFuture<PurchaseResult> f = new CompletableFuture<>();
            futures.add(f);
            clients.execute(() -> {
                try {
                    start.await();
                    f.complete(s.purchase(player, e.id(), e.price(), e.revision()).get());
                } catch (Exception ex) {
                    f.completeExceptionally(ex);
                }
            });
        }
        start.countDown();
        int success = 0;
        int insufficient = 0;
        for (CompletableFuture<PurchaseResult> f : futures) {
            PurchaseResult.Status s = f.get(30, TimeUnit.SECONDS).status();
            if (s == PurchaseResult.Status.SUCCESS) {
                success++;
            } else if (s == PurchaseResult.Status.INSUFFICIENT_FUNDS) {
                insufficient++;
            }
        }
        clients.shutdown();
        assertEquals(8, success);
        assertEquals(16, insufficient);
        assertEquals(40, balance(player));
        assertEquals(8, port.deliveredTo(player));
    }

    @Test
    void crashRecoveryNeverReplaysInterruptedDeliveries() throws Exception {
        give(player, 1_000);
        ShopEntry e = diamonds();
        long now = System.currentTimeMillis();
        // Simulate two crashes: one right after the debit (PENDING), one after delivery started (DELIVERING).
        for (PurchaseState state : List.of(PurchaseState.PENDING, PurchaseState.DELIVERING)) {
            String id = state.name().toLowerCase() + "-purchase";
            db.submit(c -> {
                PointsStore.debit(c, player, e.price(), "purchase:" + e.id(), "purchase:" + id, "test", now);
                PurchaseStore.insert(c, new PurchaseRecord(id, player, e.id(), e.price(), e.revision(), "ITEM",
                        port.snapshot(e), state, null, now, now));
                return null;
            }).get();
        }
        assertEquals(760, balance(player));
        // "Restart".
        main.shutdownNow();
        db.close();
        db = TestSupport.open(dir);
        main = TestSupport.mainThread();
        port = new FakePort(main);
        service = new PurchaseService(db, port, catalog::get, () -> 1_000_000_000L, Clock.SYSTEM, TestSupport.LOG);

        assertEquals(1, service.flagInterrupted().get());
        assertEquals(PurchaseState.NEEDS_REVIEW, record("delivering-purchase").state());
        // The safe (PENDING) one is delivered when the player joins; the ambiguous one is not touched.
        List<PurchaseResult> resumed = service.resumePending(player).get(10, TimeUnit.SECONDS);
        assertEquals(1, resumed.size());
        assertEquals(PurchaseResult.Status.SUCCESS, resumed.get(0).status());
        assertEquals(1, port.deliveredTo(player));
        assertEquals(PurchaseState.DELIVERED, record("pending-purchase").state());
        assertEquals(PurchaseState.NEEDS_REVIEW, record("delivering-purchase").state());
        // A second join does not deliver again.
        assertTrue(service.resumePending(player).get(10, TimeUnit.SECONDS).isEmpty());
        assertEquals(1, port.deliveredTo(player));
        assertEquals(760, balance(player));
        // Staff decide on the ambiguous one.
        assertTrue(service.resolve("delivering-purchase", PurchaseService.Resolution.DELIVERED, "admin").get());
        assertEquals(PurchaseState.RESOLVED, record("delivering-purchase").state());
        assertEquals(760, balance(player));
    }

    @Test
    void playerLeavingMidPurchaseIsQueuedAndDeliveredLater() throws Exception {
        give(player, 500);
        port.snapshotChecks.add(DeliveryPort.Check.OFFLINE);
        PurchaseResult r = buy(player, diamonds());
        assertEquals(PurchaseResult.Status.QUEUED, r.status());
        assertEquals(380, balance(player));
        assertEquals(PurchaseState.PENDING, record(r.purchaseId()).state());
        List<PurchaseResult> later = service.resumePending(player).get(10, TimeUnit.SECONDS);
        assertEquals(PurchaseResult.Status.SUCCESS, later.get(0).status());
        assertEquals(1, port.deliveredTo(player));
    }

    @Test
    void pendingDeliveryWithoutSpaceIsRefundedOnJoin() throws Exception {
        give(player, 500);
        port.snapshotChecks.add(DeliveryPort.Check.OFFLINE);
        PurchaseResult r = buy(player, diamonds());
        assertEquals(PurchaseResult.Status.QUEUED, r.status());
        port.snapshotChecks.add(DeliveryPort.Check.NO_SPACE);
        List<PurchaseResult> later = service.resumePending(player).get(10, TimeUnit.SECONDS);
        assertEquals(PurchaseResult.Status.REFUNDED, later.get(0).status());
        assertEquals(500, balance(player));
        assertEquals(0, port.deliveredTo(player));
    }
}
