package dev.exoquests.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exoquests.core.config.ConfigBundle;
import dev.exoquests.core.quest.QuestAction;
import dev.exoquests.core.quest.QuestDefinition;
import dev.exoquests.core.quest.QuestProgressService;
import dev.exoquests.core.storage.CompletionResult;
import dev.exoquests.core.storage.Database;
import dev.exoquests.core.storage.PointsStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestProgressServiceTest {

    @TempDir
    Path dir;
    Database db;
    ExecutorService main;
    ConfigBundle config;
    TestSupport.MutableClock clock;
    QuestProgressService service;
    final List<CompletionResult> completions = Collections.synchronizedList(new ArrayList<>());
    final List<String> loads = Collections.synchronizedList(new ArrayList<>());
    final UUID player = UUID.randomUUID();

    // 2026-10-07 12:00 BST
    static final Instant NOON = Instant.parse("2026-10-07T11:00:00Z");
    // 2026-10-08 00:00 BST
    static final Instant MIDNIGHT = Instant.parse("2026-10-07T23:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        config = TestSupport.defaults(dir);
        db = TestSupport.open(dir);
        main = TestSupport.mainThread();
        clock = new TestSupport.MutableClock(NOON);
        service = newService();
    }

    private QuestProgressService newService() {
        return new QuestProgressService(db, main, clock, config.settings().reset(), () -> config.quests(),
                () -> 1_000_000_000L, Random::new, new QuestProgressService.Listener() {
            @Override
            public void onLoaded(UUID p, boolean reset) {
                loads.add(p + ":" + reset);
            }

            @Override
            public void onCompleted(UUID p, Optional<QuestDefinition> d, CompletionResult r, boolean online) {
                completions.add(r);
            }

            @Override
            public void onError(String message, Throwable error) {
                throw new AssertionError(message, error);
            }
        });
    }

    @AfterEach
    void tearDown() {
        main.shutdownNow();
        db.close();
    }

    private <T> T onMain(java.util.concurrent.Callable<T> c) throws Exception {
        return main.submit(c).get();
    }

    private void run(Runnable r) throws Exception {
        main.submit(r).get();
        TestSupport.settle(db, main);
    }

    private QuestProgressService.SessionView view() throws Exception {
        return onMain(() -> service.view(player).orElseThrow());
    }

    private QuestAction actionFor(QuestProgressService.SlotView slot, int amount) {
        QuestDefinition d = config.quests().get(slot.questId()).orElseThrow();
        String key = d.keys().isEmpty() ? "COD" : d.keys().iterator().next();
        return new QuestAction(d.type(), key, amount);
    }

    private long balance() throws Exception {
        return db.submit(c -> PointsStore.balance(c, player)).get();
    }

    @Test
    void completesExactlyOnceAndAwardsOnlyPoints() throws Exception {
        run(() -> service.join(player));
        QuestProgressService.SessionView v = view();
        assertEquals(3, v.slots().size());
        QuestProgressService.SlotView slot = v.slots().get(0);
        String period = onMain(service::currentPeriod);
        // Spread the target over many actions, then keep going well past the target.
        for (int i = 0; i < slot.target() + 25; i++) {
            QuestAction a = actionFor(slot, 1);
            main.execute(() -> service.record(player, a, period, false));
        }
        TestSupport.settle(db, main);
        long creditedForSlot = completions.stream().filter(c -> c.slot() == slot.slot()).count();
        assertEquals(1, creditedForSlot);
        QuestProgressService.SlotView after = view().slots().get(0);
        assertTrue(after.completed());
        assertEquals(after.target(), after.progress());
        long expected = completions.stream().mapToLong(CompletionResult::pointsAwarded).sum();
        assertEquals(expected, balance());
        assertTrue(balance() >= slot.reward());
    }

    @Test
    void reconnectAndRestartKeepAssignmentAndProgress() throws Exception {
        run(() -> service.join(player));
        QuestProgressService.SessionView before = view();
        QuestProgressService.SlotView slot = before.slots().get(1);
        String period = onMain(service::currentPeriod);
        int partial = Math.max(1, slot.target() / 2);
        if (partial >= slot.target()) {
            return; // target 1 quests cannot be partially progressed
        }
        run(() -> service.record(player, actionFor(slot, partial), period, false));
        main.submit(() -> service.quit(player)).get().get();
        TestSupport.settle(db, main);

        run(() -> service.join(player));
        assertEquals(before.slots().stream().map(QuestProgressService.SlotView::questId).toList(),
                view().slots().stream().map(QuestProgressService.SlotView::questId).toList());
        assertTrue(view().slots().get(1).progress() >= partial);

        // Simulated restart: flush, close database, reopen, new service instance.
        main.submit(service::flushAll).get().get();
        db.close();
        db = TestSupport.open(dir);
        service = newService();
        run(() -> service.join(player));
        assertEquals(before.slots().stream().map(QuestProgressService.SlotView::questId).toList(),
                view().slots().stream().map(QuestProgressService.SlotView::questId).toList());
        assertTrue(view().slots().get(1).progress() >= partial);
    }

    @Test
    void resetBoundaryExpiresProgressButKeepsPoints() throws Exception {
        run(() -> service.join(player));
        QuestProgressService.SessionView today = view();
        String period = onMain(service::currentPeriod);
        assertEquals("2026-10-07", period);
        // Complete slot 0 so the player has points.
        QuestProgressService.SlotView slot0 = today.slots().get(0);
        run(() -> service.record(player, actionFor(slot0, slot0.target()), period, false));
        long points = balance();
        assertTrue(points > 0);
        // Partial progress on another slot.
        QuestProgressService.SlotView slot2 = today.slots().get(2);
        if (slot2.target() > 1) {
            run(() -> service.record(player, actionFor(slot2, 1), period, false));
        }

        // One millisecond before the reset nothing changes.
        clock.set(MIDNIGHT.minusMillis(1));
        run(() -> service.currentPeriod());
        assertEquals("2026-10-07", view().period());

        // At the reset boundary a new assignment is loaded with no progress.
        clock.set(MIDNIGHT);
        run(() -> service.currentPeriod());
        QuestProgressService.SessionView tomorrow = view();
        assertEquals("2026-10-08", tomorrow.period());
        assertFalse(tomorrow.loading());
        tomorrow.slots().forEach(s -> {
            assertEquals(0, s.progress());
            assertFalse(s.completed());
        });
        assertTrue(loads.contains(player + ":true"));
        assertEquals(points, balance(), "points never expire");

        // An action captured before the reset is discarded rather than credited to the new day.
        QuestProgressService.SlotView t0 = tomorrow.slots().get(0);
        run(() -> service.record(player, actionFor(t0, 1), period, false));
        assertEquals(0, view().slots().get(0).progress());
    }

    @Test
    void playerOfflineDuringResetGetsFreshQuestsOnJoin() throws Exception {
        run(() -> service.join(player));
        List<String> day1 = view().slots().stream().map(QuestProgressService.SlotView::questId).toList();
        main.submit(() -> service.quit(player)).get().get();
        clock.set(MIDNIGHT.plusSeconds(3600 * 30)); // two resets later
        run(() -> service.join(player));
        QuestProgressService.SessionView v = view();
        assertEquals("2026-10-09", v.period());
        assertEquals(3, v.slots().size());
        v.slots().forEach(s -> assertEquals(0, s.progress()));
        assertNotEquals(null, day1);
    }

    @Test
    void offlineCreditIsAppliedAndSeenOnJoin() throws Exception {
        run(() -> service.join(player));
        QuestProgressService.SlotView slot = view().slots().get(0);
        String period = onMain(service::currentPeriod);
        main.submit(() -> service.quit(player)).get().get();
        TestSupport.settle(db, main);
        run(() -> service.record(player, actionFor(slot, slot.target()), period, true));
        assertEquals(1, completions.size());
        run(() -> service.join(player));
        assertTrue(view().slots().get(0).completed());
        // Not credited a second time.
        run(() -> service.record(player, actionFor(slot, slot.target()), period, true));
        assertEquals(1, completions.size());
    }

    @Test
    void adminResetRerollsButKeepsEarnedPoints() throws Exception {
        run(() -> service.join(player));
        QuestProgressService.SlotView slot = view().slots().get(0);
        String period = onMain(service::currentPeriod);
        run(() -> service.record(player, actionFor(slot, slot.target()), period, false));
        long points = balance();
        main.submit(() -> service.adminReset(player)).get().get();
        TestSupport.settle(db, main);
        QuestProgressService.SessionView v = view();
        assertEquals(3, v.slots().size());
        v.slots().forEach(s -> assertFalse(s.completed()));
        assertEquals(points, balance());
    }
}
