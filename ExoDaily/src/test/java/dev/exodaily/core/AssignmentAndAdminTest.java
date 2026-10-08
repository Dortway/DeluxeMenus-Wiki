package dev.exodaily.core;

import dev.exodaily.core.claim.ClaimOutcome;
import dev.exodaily.core.config.ConfigBundle;
import dev.exodaily.core.config.ConfigLoader;
import dev.exodaily.core.reward.DaySchedule;
import dev.exodaily.core.reward.PoolEntry;
import dev.exodaily.core.reward.RewardCatalog;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.reward.RewardPool;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.service.AdminService;
import dev.exodaily.core.service.DailyView;
import dev.exodaily.core.service.PreviousDay;
import dev.exodaily.core.storage.AssignmentRecord;
import dev.exodaily.core.storage.AuditEntry;
import dev.exodaily.support.FakePlayer;
import dev.exodaily.support.Fixtures;
import dev.exodaily.support.Harness;
import dev.exodaily.support.TestPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentAndAdminTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    Harness h;

    @BeforeEach
    void setUp() {
        h = new Harness(dir, Fixtures.bundledConfig().rewards());
    }

    @AfterEach
    void tearDown() throws Exception {
        h.close();
    }

    private static Map<RewardPosition, String> ids(DailyView view) {
        Map<RewardPosition, String> ids = new HashMap<>();
        view.assignments().forEach((position, record) -> ids.put(position, record.reward().id()));
        return ids;
    }

    @Test
    void allThreePositionsAreAssignedOnFirstViewEvenForStandardPlayers() {
        DailyView view = h.view(ALEX);
        assertEquals(3, view.assignments().size());
        assertEquals(Set.of(RewardPosition.values()), view.assignments().keySet());
    }

    @Test
    void onlyTheViewedDayIsAssigned() {
        h.view(ALEX);
        for (int day = 2; day <= 30; day++) {
            int d = day;
            assertTrue(h.store.transaction(tx -> tx.assignments(ALEX, 1, d)).isEmpty(), "day " + day + " was assigned early");
        }
    }

    @Test
    void assignmentsAreStableAcrossReopenRestartAndReload() {
        DailyView first = h.view(ALEX);
        Map<RewardPosition, String> original = ids(first);
        assertEquals(original, ids(h.view(ALEX)));

        h.restart();
        assertEquals(original, ids(h.view(ALEX)));

        // Reload a completely different catalog: assigned rewards must not change, and the
        // stored snapshot (not the new definition) is what gets shown and delivered.
        Map<String, RewardDefinition> rewards = new HashMap<>();
        rewards.put("dirt", Fixtures.reward("dirt", "DIRT", 1, 1));
        for (String id : original.values()) {
            rewards.put(id, Fixtures.reward(id, "DIRT", 1, 1));
        }
        RewardPool pool = new RewardPool("p", "", List.of(new PoolEntry("dirt", 1)), null);
        DaySchedule schedule = new DaySchedule(Map.of(RewardPosition.STANDARD, "p",
                RewardPosition.PREMIUM_ONE, "p", RewardPosition.PREMIUM_TWO, "p"));
        h.catalog.set(new RewardCatalog(rewards, Map.of("p", pool), schedule, Map.of(), false, null, 4, Set.of()));
        DailyView afterReload = h.view(ALEX);
        assertEquals(original, ids(afterReload));
        for (RewardPosition position : RewardPosition.values()) {
            AssignmentRecord before = first.assignments().get(position);
            AssignmentRecord after = afterReload.assignments().get(position);
            assertEquals(before.reward(), after.reward());
            assertFalse(after.reward().material().equals("DIRT"));
        }
        FakePlayer alex = new FakePlayer();
        assertEquals(ClaimOutcome.SUCCESS, h.claim(afterReload, RewardPosition.STANDARD, alex).outcome());
        assertEquals(original.get(RewardPosition.STANDARD), alex.received.getFirst());
    }

    @Test
    void premiumChangesDoNotRerollAssignments() {
        DailyView view = h.view(ALEX);
        Map<RewardPosition, String> original = ids(view);
        FakePlayer alex = new FakePlayer();
        h.claim(view, RewardPosition.PREMIUM_ONE, alex); // locked, but must not disturb anything
        alex.premium = true;
        assertEquals(original, ids(h.view(ALEX)));
    }

    @Test
    void playersAreRandomizedIndependently() {
        // With many players, at least two different selections occur; coincidences are allowed.
        Set<Map<RewardPosition, String>> distinct = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            distinct.add(ids(h.view(new UUID(7, i))));
        }
        assertTrue(distinct.size() > 1);
    }

    @Test
    void skippedDaysAreNeverAssigned() {
        h.view(ALEX);
        h.source.advance(Duration.ofDays(4));
        DailyView dayFive = h.view(ALEX);
        assertEquals(5, dayFive.state().day());
        for (int day = 2; day <= 4; day++) {
            int d = day;
            assertTrue(h.store.transaction(tx -> tx.assignments(ALEX, 1, d)).isEmpty());
        }
        assertEquals(PreviousDay.Kind.MISSED, dayFive.previousDay().kind());
    }

    @Test
    void previousDayReportsClaimedMissedAndNone() {
        DailyView first = h.view(ALEX);
        assertEquals(PreviousDay.Kind.NONE, first.previousDay().kind());
        h.claim(first, RewardPosition.STANDARD, new FakePlayer());
        h.source.advance(Duration.ofDays(1));
        DailyView second = h.view(ALEX);
        assertEquals(PreviousDay.Kind.CLAIMED, second.previousDay().kind());
        assertEquals(1, second.previousDay().claimed());
        h.source.advance(Duration.ofDays(1));
        assertEquals(PreviousDay.Kind.MISSED, h.view(ALEX).previousDay().kind());
    }

    @Test
    void setDayPreservesClaimsAndIsAudited() throws Exception {
        FakePlayer alex = new FakePlayer();
        DailyView dayOne = h.view(ALEX);
        Map<RewardPosition, String> dayOneRewards = ids(dayOne);
        assertEquals(ClaimOutcome.SUCCESS, h.claim(dayOne, RewardPosition.STANDARD, alex).outcome());

        h.admin.setDay(ALEX, "alex", 5, "console");
        DailyView dayFive = h.view(ALEX);
        assertEquals(5, dayFive.state().day());
        assertEquals(ClaimOutcome.SUCCESS, h.claim(dayFive, RewardPosition.STANDARD, alex).outcome());

        h.admin.setDay(ALEX, "alex", 1, "console");
        DailyView backToOne = h.view(ALEX);
        assertEquals(dayOneRewards, ids(backToOne), "assignments are kept, not rerolled");
        assertEquals(ClaimOutcome.ALREADY_CLAIMED, h.claim(backToOne, RewardPosition.STANDARD, alex).outcome());
        assertEquals(2, alex.received.size());

        List<AuditEntry> audit = h.store.transaction(tx -> tx.recentAudit(10));
        assertEquals(2, audit.stream().filter(entry -> entry.action().equals("setday")).count());
        assertTrue(Files.readString(dir.resolve("audit.log")).contains("setday"));
    }

    @Test
    void staleMenuIsRejectedAfterAnAdminChange() {
        FakePlayer alex = new FakePlayer();
        DailyView dayOne = h.view(ALEX);
        h.admin.setDay(ALEX, "alex", 3, "console");
        assertEquals(ClaimOutcome.STALE, h.claim(dayOne, RewardPosition.STANDARD, alex).outcome());
        assertTrue(alex.received.isEmpty());
    }

    @Test
    void explicitResetAllowsRewardsToBeEarnedAgain() {
        FakePlayer alex = new FakePlayer();
        DailyView before = h.view(ALEX);
        assertEquals(ClaimOutcome.SUCCESS, h.claim(before, RewardPosition.STANDARD, alex).outcome());

        assertTrue(h.admin.reset(ALEX, "alex", "console").isPresent());
        DailyView after = h.view(ALEX);
        assertEquals(2, after.state().cycleNumber());
        assertEquals(1, after.state().day());
        // Documented and warned: the same calendar day can be claimed again after a reset.
        assertEquals(ClaimOutcome.SUCCESS, h.claim(after, RewardPosition.STANDARD, alex).outcome());
        assertEquals(2, alex.received.size());
        // The old menu cannot be used to claim the old cycle again.
        assertEquals(ClaimOutcome.STALE, h.claim(before, RewardPosition.STANDARD, alex).outcome());
        assertTrue(h.store.transaction(tx -> tx.recentAudit(5)).stream().anyMatch(e -> e.action().equals("reset")));
    }

    @Test
    void statusIsReadOnly() {
        assertTrue(h.admin.status(SAM).isEmpty());
        assertTrue(h.store.transaction(tx -> tx.profile(SAM)).isEmpty());
        assertTrue(h.admin.reset(SAM, "sam", "console").isEmpty());
        h.view(ALEX);
        AdminService.Status status = h.admin.status(ALEX).orElseThrow();
        assertEquals(1, status.state().day());
        assertEquals(3, status.assignments().size());
    }

    @Test
    void configuredCycleLengthIsHonouredFromTheBundledFiles() {
        Map<String, String> files = new HashMap<>(Fixtures.bundledFiles());
        files.put(ConfigLoader.CONFIG, files.get(ConfigLoader.CONFIG).replace("cycle-length: 30", "cycle-length: 7"));
        ConfigLoader.Result result = new ConfigLoader(new TestPlatform()).load(files, Fixtures.bundledFiles());
        assertTrue(result.success(), () -> result.errors().toString());
        ConfigBundle bundle = result.bundle();
        assertEquals(7, bundle.settings().cycleLength());
        // Overrides for days 8-30 are reported as unused.
        assertTrue(result.warnings().stream().anyMatch(issue -> issue.path().equals("schedule.days.8")));
    }
}
