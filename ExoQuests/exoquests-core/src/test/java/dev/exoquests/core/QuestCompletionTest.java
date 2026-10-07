package dev.exoquests.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exoquests.core.config.ConfigBundle;
import dev.exoquests.core.quest.QuestAction;
import dev.exoquests.core.quest.QuestDefinition;
import dev.exoquests.core.storage.AssignmentRow;
import dev.exoquests.core.storage.CompletionResult;
import dev.exoquests.core.storage.Database;
import dev.exoquests.core.storage.PointsStore;
import dev.exoquests.core.storage.QuestStore;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Database-level guarantees: stable assignments and exactly-once completion credit. */
class QuestCompletionTest {

    @TempDir
    Path dir;
    Database db;
    ConfigBundle config;
    final UUID player = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        config = TestSupport.defaults(dir);
        db = TestSupport.open(dir);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private List<AssignmentRow> assign(String period, long seed) throws Exception {
        return db.submit(c -> QuestStore.loadOrAssign(c, player, period, config.quests(), new Random(seed), 0L)).get();
    }

    @Test
    void assignmentIsStableAcrossReloadsAndRestarts() throws Exception {
        List<AssignmentRow> first = assign("2026-10-07", 1);
        assertEquals(3, first.size());
        assertEquals(3, new HashSet<>(first.stream().map(AssignmentRow::questId).toList()).size());
        // A different random seed must not reroll an existing assignment.
        assertEquals(first, assign("2026-10-07", 999));
        db.close();
        db = TestSupport.open(dir);
        assertEquals(first, assign("2026-10-07", 12345));
        // A new period gets its own assignment with zero progress.
        List<AssignmentRow> next = assign("2026-10-08", 7);
        assertEquals(3, next.size());
        next.forEach(r -> assertEquals(0, r.progress()));
    }

    @Test
    void concurrentCompletionCreditsExactlyOnce() throws Exception {
        AssignmentRow slot = assign("2026-10-07", 3).get(0);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<CompletionResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            CompletableFuture<CompletionResult> f = new CompletableFuture<>();
            results.add(f);
            pool.execute(() -> {
                try {
                    start.await();
                    f.complete(db.submit(c -> QuestStore.complete(c, player, "2026-10-07", slot.slot(),
                            1_000_000_000L, 1L)).get());
                } catch (Exception e) {
                    f.completeExceptionally(e);
                }
            });
        }
        start.countDown();
        int credited = 0;
        for (CompletableFuture<CompletionResult> f : results) {
            if (f.get().credited()) {
                credited++;
            }
        }
        pool.shutdown();
        assertEquals(1, credited);
        assertEquals(slot.reward(), db.submit(c -> PointsStore.balance(c, player)).get());
        assertEquals(1, db.submit(c -> QuestStore.completionCount(c, player)).get());
        assertEquals(1L, countLedger());
    }

    @Test
    void progressPersistenceNeverReachesTargetWithoutCompletion() throws Exception {
        AssignmentRow slot = assign("2026-10-07", 5).get(0);
        db.submit(c -> {
            QuestStore.saveProgress(c, player, "2026-10-07", slot.slot(), slot.target() + 50);
            return null;
        }).get();
        AssignmentRow after = assign("2026-10-07", 5).get(0);
        assertEquals(slot.target() - 1, after.progress());
        assertFalse(after.completed());
        // Progress never decreases.
        db.submit(c -> {
            QuestStore.saveProgress(c, player, "2026-10-07", slot.slot(), 1);
            return null;
        }).get();
        assertEquals(slot.target() - 1, assign("2026-10-07", 5).get(0).progress());
    }

    @Test
    void offlineProgressCompletesOnceAndThenStops() throws Exception {
        List<AssignmentRow> rows = assign("2026-10-07", 11);
        AssignmentRow slot = rows.get(0);
        QuestDefinition def = config.quests().get(slot.questId()).orElseThrow();
        String key = def.keys().isEmpty() ? "ANYTHING" : def.keys().iterator().next();
        QuestAction action = new QuestAction(def.type(), key, 1_000_000);
        List<CompletionResult> first = db.submit(c -> QuestStore.applyOffline(c, player, "2026-10-07", action,
                config.quests(), new Random(1), 1_000_000_000L, 1L)).get();
        assertTrue(first.stream().anyMatch(CompletionResult::credited));
        List<CompletionResult> second = db.submit(c -> QuestStore.applyOffline(c, player, "2026-10-07", action,
                config.quests(), new Random(1), 1_000_000_000L, 1L)).get();
        assertTrue(second.isEmpty());
        long expected = rows.stream().filter(r -> config.quests().get(r.questId()).orElseThrow().matches(action))
                .mapToLong(AssignmentRow::reward).sum();
        assertEquals(expected, db.submit(c -> PointsStore.balance(c, player)).get());
    }

    @Test
    void balanceIsCappedAndNeverOverflows() throws Exception {
        long max = 100;
        PointsStore.Change a = db.submit(c -> PointsStore.credit(c, player, 80, max, "t", "r1", "test", 0)).get();
        PointsStore.Change b = db.submit(c -> PointsStore.credit(c, player, 80, max, "t", "r2", "test", 0)).get();
        PointsStore.Change d = db.submit(c -> PointsStore.credit(c, player, Long.MAX_VALUE, max, "t", "r3", "test", 0)).get();
        assertEquals(80, a.delta());
        assertEquals(20, b.delta());
        assertFalse(d.applied());
        assertEquals(PointsStore.Failure.AT_MAXIMUM, d.failure());
        assertEquals(100L, db.submit(c -> PointsStore.balance(c, player)).get());
        // Replaying a reference is rejected.
        PointsStore.Change dup = db.submit(c -> PointsStore.credit(c, player, 1, 1000, "t", "r1", "test", 0)).get();
        assertEquals(PointsStore.Failure.DUPLICATE_REFERENCE, dup.failure());
    }

    private long countLedger() throws Exception {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM point_ledger WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }).get();
    }
}
