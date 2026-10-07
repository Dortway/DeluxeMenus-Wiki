package dev.exo.dailyspinner.storage;

import dev.exo.dailyspinner.reward.RewardSnapshot;
import dev.exo.dailyspinner.reward.RewardType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SpinRepositoryTest {

    private static final long DAY = 86_400_000L;
    private static final long T0 = 1_700_000_000_000L;

    @TempDir
    Path dir;

    private final List<Connection> connections = new ArrayList<>();
    private SpinRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        repo = new SpinRepository(open());
    }

    @AfterEach
    void tearDown() throws SQLException {
        for (Connection c : connections) {
            c.close();
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("test.db"));
        Database.configure(c, 10_000);
        Migrations.migrate(c);
        connections.add(c);
        return c;
    }

    private static RewardSnapshot item(String id, int amount) {
        return new RewardSnapshot(id, RewardType.ITEM, new byte[]{1, 2, 3}, amount, List.of(),
                new byte[]{9}, "<aqua>" + id, "rare", null);
    }

    private static RewardSnapshot command(String id) {
        return new RewardSnapshot(id, RewardType.COMMAND, null, 0, List.of("say {player}", "xp add {player} 5"),
                null, id, "common", "");
    }

    private static String op() {
        return UUID.randomUUID().toString();
    }

    @Test
    void migrationsAreIdempotent() throws SQLException {
        Connection c = connections.get(0);
        assertEquals(Migrations.latestVersion(), Migrations.migrate(c));
        assertEquals(Migrations.latestVersion(), Migrations.currentVersion(c));
    }

    @Test
    void dailySpinThenCooldown() throws SQLException {
        UUID player = UUID.randomUUID();
        ReservationResult first = repo.reserve(player, op(), item("diamonds", 5), T0, DAY, ConsumeOrder.DAILY_FIRST);
        assertEquals(ReservationResult.Outcome.RESERVED, first.outcome());
        assertEquals(SpinSource.DAILY, first.spin().source());

        // Deliver it so it is no longer the active spin.
        assertTrue(repo.beginDelivery(first.spin().id(), player, T0).isPresent());
        assertTrue(repo.completeDelivery(first.spin().id(), player, null, 0, T0, "ok"));

        ReservationResult second = repo.reserve(player, op(), item("diamonds", 5), T0 + 1000, DAY, ConsumeOrder.DAILY_FIRST);
        assertEquals(ReservationResult.Outcome.UNAVAILABLE, second.outcome());
        assertEquals(DAY - 1000, second.remainingMillis());

        ReservationResult third = repo.reserve(player, op(), item("diamonds", 5), T0 + DAY, DAY, ConsumeOrder.DAILY_FIRST);
        assertEquals(ReservationResult.Outcome.RESERVED, third.outcome());
    }

    @Test
    void dailyIsConsumedBeforeBonusByDefault() throws SQLException {
        UUID player = UUID.randomUUID();
        assertEquals(2, repo.addBonusSpins(player, 2, 100, T0));
        ReservationResult r = repo.reserve(player, op(), item("x", 1), T0, DAY, ConsumeOrder.DAILY_FIRST);
        assertEquals(SpinSource.DAILY, r.spin().source());
        assertEquals(2, repo.loadPlayer(player).bonusSpins());
        deliver(player, r);

        ReservationResult b = repo.reserve(player, op(), item("x", 1), T0 + 10, DAY, ConsumeOrder.DAILY_FIRST);
        assertEquals(SpinSource.BONUS, b.spin().source());
        assertEquals(1, repo.loadPlayer(player).bonusSpins());
    }

    @Test
    void bonusFirstOrderUsesBonusSpin() throws SQLException {
        UUID player = UUID.randomUUID();
        repo.addBonusSpins(player, 1, 100, T0);
        ReservationResult r = repo.reserve(player, op(), item("x", 1), T0, DAY, ConsumeOrder.BONUS_FIRST);
        assertEquals(SpinSource.BONUS, r.spin().source());
        PlayerData data = repo.loadPlayer(player);
        assertEquals(0, data.bonusSpins());
        assertNull(data.lastDailySpin(), "daily spin must remain untouched");
    }

    @Test
    void existingUndeliveredSpinIsReturnedInsteadOfRerolling() throws SQLException {
        UUID player = UUID.randomUUID();
        repo.addBonusSpins(player, 5, 100, T0);
        ReservationResult first = repo.reserve(player, op(), item("legendary", 1), T0, DAY, ConsumeOrder.DAILY_FIRST);
        ReservationResult again = repo.reserve(player, op(), item("common", 1), T0 + 5, DAY, ConsumeOrder.DAILY_FIRST);
        assertEquals(ReservationResult.Outcome.EXISTING, again.outcome());
        assertEquals(first.spin().id(), again.spin().id());
        assertEquals("legendary", again.spin().snapshot().rewardId());
        assertEquals(5, repo.loadPlayer(player).bonusSpins(), "no extra entitlement consumed");
    }

    @Test
    void replayedOperationIdIsRejected() throws SQLException {
        UUID player = UUID.randomUUID();
        String id = op();
        ReservationResult first = repo.reserve(player, id, item("x", 1), T0, DAY, ConsumeOrder.DAILY_FIRST);
        deliver(player, first);
        repo.resetCooldown(player, T0);
        ReservationResult replay = repo.reserve(player, id, item("x", 1), T0 + 1, DAY, ConsumeOrder.DAILY_FIRST);
        assertEquals(ReservationResult.Outcome.DUPLICATE, replay.outcome());
        assertNull(repo.loadPlayer(player).lastDailySpin(), "duplicate must not consume the reset daily spin");
    }

    @Test
    void snapshotSurvivesRoundTrip() throws SQLException {
        UUID player = UUID.randomUUID();
        ReservationResult r = repo.reserve(player, op(), command("money"), T0, DAY, ConsumeOrder.DAILY_FIRST);
        SpinRecord stored = repo.findSpin(r.spin().id()).orElseThrow();
        assertEquals(List.of("say {player}", "xp add {player} 5"), stored.snapshot().commands());
        assertEquals(RewardType.COMMAND, stored.snapshot().type());
        assertEquals("", stored.snapshot().announcement());
    }

    @Test
    void deliveryTransitionHappensExactlyOnce() throws SQLException {
        UUID player = UUID.randomUUID();
        ReservationResult r = repo.reserve(player, op(), item("x", 3), T0, DAY, ConsumeOrder.DAILY_FIRST);
        String id = r.spin().id();
        assertTrue(repo.beginDelivery(id, UUID.randomUUID(), T0).isEmpty(), "other players cannot deliver it");
        assertTrue(repo.beginDelivery(id, player, T0).isPresent());
        assertTrue(repo.beginDelivery(id, player, T0).isEmpty(), "replayed completion callback is ignored");
        assertTrue(repo.completeDelivery(id, player, new byte[]{1}, 2, T0, "partial"));
        assertFalse(repo.completeDelivery(id, player, new byte[]{1}, 2, T0, "partial"), "second completion ignored");
        assertEquals(1, repo.countPending(player), "overflow stored exactly once");
    }

    @Test
    void revertOnlyFromDelivering() throws SQLException {
        UUID player = UUID.randomUUID();
        ReservationResult r = repo.reserve(player, op(), item("x", 1), T0, DAY, ConsumeOrder.DAILY_FIRST);
        assertFalse(repo.revertDelivery(r.spin().id(), T0));
        repo.beginDelivery(r.spin().id(), player, T0);
        assertTrue(repo.revertDelivery(r.spin().id(), T0));
        assertEquals(1, repo.findReservedSpins(player).size());
    }

    @Test
    void concurrentReservationsAcrossConnectionsConsumeOnce() throws Exception {
        UUID player = UUID.randomUUID();
        int threads = 16;
        List<SpinRepository> repos = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            repos.add(new SpinRepository(open()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReservationResult>> futures = new ArrayList<>();
        for (SpinRepository r : repos) {
            futures.add(pool.submit(() -> {
                start.await();
                return r.reserve(player, op(), item("x", 1), T0, DAY, ConsumeOrder.DAILY_FIRST);
            }));
        }
        start.countDown();
        int reserved = 0;
        int existing = 0;
        for (Future<ReservationResult> f : futures) {
            ReservationResult res = f.get(30, TimeUnit.SECONDS);
            if (res.outcome() == ReservationResult.Outcome.RESERVED) {
                reserved++;
            } else if (res.outcome() == ReservationResult.Outcome.EXISTING) {
                existing++;
            }
        }
        pool.shutdown();
        assertEquals(1, reserved, "exactly one reservation may consume the daily spin");
        assertEquals(threads - 1, existing, "everyone else sees the same undelivered spin");
    }

    @Test
    void concurrentBonusSpinsNeverGoNegative() throws Exception {
        UUID player = UUID.randomUUID();
        repo.addBonusSpins(player, 3, 100, T0);
        // Use up the daily spin first so only bonus spins remain.
        deliver(player, repo.reserve(player, op(), item("x", 1), T0, DAY, ConsumeOrder.DAILY_FIRST));
        int successes = 0;
        for (int round = 0; round < 10; round++) {
            List<SpinRepository> repos = List.of(new SpinRepository(open()), new SpinRepository(open()), new SpinRepository(open()));
            ExecutorService pool = Executors.newFixedThreadPool(3);
            List<Future<ReservationResult>> futures = new ArrayList<>();
            for (SpinRepository r : repos) {
                futures.add(pool.submit(() -> r.reserve(player, op(), item("x", 1), T0 + 1, DAY, ConsumeOrder.DAILY_FIRST)));
            }
            for (Future<ReservationResult> f : futures) {
                ReservationResult res = f.get(30, TimeUnit.SECONDS);
                if (res.outcome() == ReservationResult.Outcome.RESERVED) {
                    successes++;
                    deliver(player, res);
                }
            }
            pool.shutdown();
        }
        assertEquals(3, successes);
        assertEquals(0, repo.loadPlayer(player).bonusSpins());
    }

    @Test
    void bonusCapIsEnforced() throws SQLException {
        UUID player = UUID.randomUUID();
        assertEquals(10, repo.addBonusSpins(player, 10, 10, T0));
        assertEquals(-1, repo.addBonusSpins(player, 1, 10, T0));
        assertThrows(IllegalArgumentException.class, () -> repo.addBonusSpins(player, 0, 10, T0));
    }

    @Test
    void claimLocksRowsAndCompletionIsNotReplayable() throws SQLException {
        UUID player = UUID.randomUUID();
        ReservationResult r = repo.reserve(player, op(), item("x", 10), T0, DAY, ConsumeOrder.DAILY_FIRST);
        repo.beginDelivery(r.spin().id(), player, T0);
        repo.completeDelivery(r.spin().id(), player, new byte[]{7}, 10, T0, "inventory full");

        String claimA = op();
        List<PendingItem> locked = repo.beginClaim(player, claimA, 50, T0);
        assertEquals(1, locked.size());
        assertEquals(10, locked.get(0).amount());
        assertTrue(repo.beginClaim(player, op(), 50, T0).isEmpty(), "concurrent claim finds nothing");

        // 4 did not fit -> remains pending with amount 4.
        assertEquals(1, repo.completeClaim(claimA, Map.of(locked.get(0).id(), 4), T0));
        assertEquals(0, repo.completeClaim(claimA, Map.of(locked.get(0).id(), 0), T0), "replay ignored");
        assertEquals(1, repo.countPending(player));

        String claimB = op();
        List<PendingItem> again = repo.beginClaim(player, claimB, 50, T0);
        assertEquals(4, again.get(0).amount());
        assertEquals(1, repo.completeClaim(claimB, Map.of(again.get(0).id(), 0), T0));
        assertEquals(0, repo.countPending(player));
    }

    @Test
    void interruptedDeliveriesAreFlaggedNotReplayed() throws SQLException {
        UUID player = UUID.randomUUID();
        ReservationResult cmd = repo.reserve(player, op(), command("money"), T0, DAY, ConsumeOrder.DAILY_FIRST);
        repo.beginDelivery(cmd.spin().id(), player, T0);
        // Simulated crash: no completeDelivery.
        List<ReconcileEntry> flagged = repo.flagInterruptedDeliveries(T0 + 5000);
        assertEquals(1, flagged.size());
        assertEquals("S:" + cmd.spin().id(), flagged.get(0).key());
        assertEquals("UNCERTAIN", flagged.get(0).status());
        assertTrue(repo.findReservedSpins(player).isEmpty(), "uncertain spins are never auto-resumed");
        assertFalse(repo.loadPlayer(player).activeSpin());

        SpinRepository.Resolution resolution = repo.resolve("S:" + cmd.spin().id(), false, T0 + 6000, "console");
        assertTrue(resolution.found());
        assertFalse(repo.resolve("S:" + cmd.spin().id(), true, T0 + 7000, "console").found(), "cannot resolve twice");
    }

    @Test
    void uncertainItemRegrantGoesToPending() throws SQLException {
        UUID player = UUID.randomUUID();
        ReservationResult r = repo.reserve(player, op(), item("x", 3), T0, DAY, ConsumeOrder.DAILY_FIRST);
        repo.beginDelivery(r.spin().id(), player, T0);
        repo.flagInterruptedDeliveries(T0 + 10);
        assertTrue(repo.resolve("S:" + r.spin().id(), true, T0 + 20, "admin").found());
        assertEquals(1, repo.countPending(player));
    }

    @Test
    void interruptedClaimIsFlagged() throws SQLException {
        UUID player = UUID.randomUUID();
        ReservationResult r = repo.reserve(player, op(), item("x", 3), T0, DAY, ConsumeOrder.DAILY_FIRST);
        repo.beginDelivery(r.spin().id(), player, T0);
        repo.completeDelivery(r.spin().id(), player, new byte[]{1}, 3, T0, "full");
        List<PendingItem> locked = repo.beginClaim(player, op(), 10, T0);
        assertEquals(1, locked.size());
        List<ReconcileEntry> flagged = repo.flagInterruptedDeliveries(T0 + 5000);
        assertEquals(1, flagged.size());
        assertEquals("P:" + locked.get(0).id(), flagged.get(0).key());
        assertEquals(0, repo.countPending(player));
        assertTrue(repo.resolve(flagged.get(0).key(), true, T0 + 6000, "admin").found());
        assertEquals(1, repo.countPending(player));
    }

    @Test
    void offlineItemMovesToPending() throws SQLException {
        UUID player = UUID.randomUUID();
        ReservationResult r = repo.reserve(player, op(), item("x", 3), T0, DAY, ConsumeOrder.DAILY_FIRST);
        Optional<SpinRecord> d = repo.beginDelivery(r.spin().id(), player, T0);
        assertTrue(d.isPresent());
        assertTrue(repo.moveReservedToPending(r.spin().id(), player, T0));
        assertFalse(repo.moveReservedToPending(r.spin().id(), player, T0));
        assertEquals(1, repo.countPending(player));
    }

    @Test
    void resetCooldownMakesDailyReady() throws SQLException {
        UUID player = UUID.randomUUID();
        deliver(player, repo.reserve(player, op(), item("x", 1), T0, DAY, ConsumeOrder.DAILY_FIRST));
        assertEquals(ReservationResult.Outcome.UNAVAILABLE,
                repo.reserve(player, op(), item("x", 1), T0 + 1, DAY, ConsumeOrder.DAILY_FIRST).outcome());
        repo.resetCooldown(player, T0 + 2);
        assertEquals(ReservationResult.Outcome.RESERVED,
                repo.reserve(player, op(), item("x", 1), T0 + 3, DAY, ConsumeOrder.DAILY_FIRST).outcome());
    }

    private void deliver(UUID player, ReservationResult r) throws SQLException {
        assertEquals(ReservationResult.Outcome.RESERVED, r.outcome());
        assertTrue(repo.beginDelivery(r.spin().id(), player, T0).isPresent());
        assertTrue(repo.completeDelivery(r.spin().id(), player, null, 0, T0, "ok"));
    }
}
