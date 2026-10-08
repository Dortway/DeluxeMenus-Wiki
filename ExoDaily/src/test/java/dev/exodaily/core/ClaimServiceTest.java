package dev.exodaily.core;

import dev.exodaily.core.claim.ClaimOutcome;
import dev.exodaily.core.claim.ClaimResult;
import dev.exodaily.core.claim.ClickGuard;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.service.AdminService;
import dev.exodaily.core.service.DailyView;
import dev.exodaily.core.service.PositionStatus;
import dev.exodaily.core.storage.ClaimRecord;
import dev.exodaily.core.storage.ClaimState;
import dev.exodaily.support.FakePlayer;
import dev.exodaily.support.Fixtures;
import dev.exodaily.support.Harness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimServiceTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @TempDir
    Path dir;
    Harness h;

    @BeforeEach
    void setUp() {
        h = new Harness(dir, Fixtures.smallCatalog(true));
    }

    @AfterEach
    void tearDown() throws Exception {
        h.close();
    }

    private ClaimRecord claimRow(DailyView view, RewardPosition position) {
        return h.store.transaction(tx -> tx.claims(ALEX, view.state().cycleNumber(), view.state().day()).get(position));
    }

    @Nested
    class TierLimits {

        @Test
        void standardPlayerGetsAtMostOneRewardPerDay() {
            FakePlayer alex = new FakePlayer();
            DailyView view = h.view(ALEX);
            assertEquals(ClaimOutcome.SUCCESS, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertEquals(ClaimOutcome.LOCKED, h.claim(view, RewardPosition.PREMIUM_ONE, alex).outcome());
            assertEquals(ClaimOutcome.LOCKED, h.claim(view, RewardPosition.PREMIUM_TWO, alex).outcome());
            assertEquals(ClaimOutcome.ALREADY_CLAIMED, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertEquals(1, alex.received.size());
            // Locked attempts leave no claim behind.
            assertEquals(null, claimRow(view, RewardPosition.PREMIUM_ONE));
            DailyView after = h.view(ALEX);
            assertEquals(1, after.claimedCount(false));
            assertEquals(PositionStatus.LOCKED, after.status(RewardPosition.PREMIUM_ONE, false));
        }

        @Test
        void premiumPlayerGetsThreeRewardsInTotal() {
            FakePlayer alex = new FakePlayer().premium(true);
            DailyView view = h.view(ALEX);
            for (RewardPosition position : RewardPosition.values()) {
                assertEquals(ClaimOutcome.SUCCESS, h.claim(view, position, alex).outcome());
            }
            for (RewardPosition position : RewardPosition.values()) {
                assertEquals(ClaimOutcome.ALREADY_CLAIMED, h.claim(view, position, alex).outcome());
            }
            assertEquals(3, alex.received.size());
            assertEquals(3, h.view(ALEX).claimedCount(true));
        }

        @Test
        void upgradingAfterTheStandardClaimUnlocksTodaysPremiumPositions() {
            FakePlayer alex = new FakePlayer();
            DailyView view = h.view(ALEX);
            assertEquals(ClaimOutcome.SUCCESS, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            alex.premium = true;
            assertEquals(ClaimOutcome.SUCCESS, h.claim(view, RewardPosition.PREMIUM_ONE, alex).outcome());
            assertEquals(ClaimOutcome.SUCCESS, h.claim(view, RewardPosition.PREMIUM_TWO, alex).outcome());
            assertEquals(3, alex.received.size());
        }

        @Test
        void removingAndRestoringPremiumNeverAllowsDuplicates() {
            FakePlayer alex = new FakePlayer().premium(true);
            DailyView view = h.view(ALEX);
            assertEquals(ClaimOutcome.SUCCESS, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertEquals(ClaimOutcome.SUCCESS, h.claim(view, RewardPosition.PREMIUM_ONE, alex).outcome());
            alex.premium = false;
            assertEquals(ClaimOutcome.LOCKED, h.claim(view, RewardPosition.PREMIUM_TWO, alex).outcome());
            assertEquals(ClaimOutcome.ALREADY_CLAIMED, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            alex.premium = true;
            h.restart(); // reconnect / restart must not reset anything either
            DailyView reopened = h.view(ALEX);
            assertEquals(ClaimOutcome.ALREADY_CLAIMED, h.claim(reopened, RewardPosition.STANDARD, alex).outcome());
            assertEquals(ClaimOutcome.ALREADY_CLAIMED, h.claim(reopened, RewardPosition.PREMIUM_ONE, alex).outcome());
            assertEquals(ClaimOutcome.SUCCESS, h.claim(reopened, RewardPosition.PREMIUM_TWO, alex).outcome());
            assertEquals(3, alex.received.size());
            assertEquals(3, Set3.of(alex.received));
        }

        @Test
        void premiumRevokedMidClaimDeliversNothing() {
            FakePlayer alex = new FakePlayer().premium(true);
            alex.afterCheck = () -> alex.premium = false;
            DailyView view = h.view(ALEX);
            assertEquals(ClaimOutcome.LOCKED, h.claim(view, RewardPosition.PREMIUM_ONE, alex).outcome());
            assertTrue(alex.received.isEmpty());
            assertEquals(null, claimRow(view, RewardPosition.PREMIUM_ONE));
        }
    }

    /** Counts distinct entries; a tiny helper to keep assertions readable. */
    private static final class Set3 {
        static int of(List<String> values) {
            return (int) values.stream().distinct().count();
        }
    }

    @Nested
    class DatesAndStaleMenus {

        @Test
        void menuFromBeforeMidnightCannotClaimAfterMidnight() {
            h.source.set(Harness.at("2026-01-01T23:59:30"));
            FakePlayer alex = new FakePlayer();
            DailyView beforeMidnight = h.view(ALEX);
            h.source.advance(Duration.ofSeconds(45));
            ClaimResult result = h.claim(beforeMidnight, RewardPosition.STANDARD, alex);
            assertEquals(ClaimOutcome.STALE, result.outcome());
            assertTrue(alex.received.isEmpty());
            // Day 1 was missed for good; the new day is claimable.
            DailyView today = h.view(ALEX);
            assertEquals(2, today.state().day());
            assertEquals(ClaimOutcome.SUCCESS, h.claim(today, RewardPosition.STANDARD, alex).outcome());
        }

        @Test
        void missedDaysCannotBeCaughtUp() {
            FakePlayer alex = new FakePlayer();
            DailyView dayOne = h.view(ALEX);
            h.source.advance(Duration.ofDays(4));
            assertEquals(ClaimOutcome.STALE, h.claim(dayOne, RewardPosition.STANDARD, alex).outcome());
            DailyView dayFive = h.view(ALEX);
            assertEquals(5, dayFive.state().day());
            // A forged request for an earlier day of the same cycle is rejected server-side.
            var forged = new dev.exodaily.core.claim.ClaimRequest(ALEX, 1, 3, dayFive.state().dateOfDay(3), RewardPosition.STANDARD);
            assertEquals(ClaimOutcome.STALE, h.claims.claim(forged, alex).join().outcome());
            assertTrue(alex.received.isEmpty());
        }

        @Test
        void dayThirtyRollsIntoANewClaimableCycle() {
            FakePlayer alex = new FakePlayer();
            h.view(ALEX);
            h.source.advance(Duration.ofDays(29));
            DailyView dayThirty = h.view(ALEX);
            assertEquals(30, dayThirty.state().day());
            assertEquals(ClaimOutcome.SUCCESS, h.claim(dayThirty, RewardPosition.STANDARD, alex).outcome());
            h.source.advance(Duration.ofDays(1));
            DailyView nextCycle = h.view(ALEX);
            assertEquals(2, nextCycle.state().cycleNumber());
            assertEquals(1, nextCycle.state().day());
            assertEquals(ClaimOutcome.SUCCESS, h.claim(nextCycle, RewardPosition.STANDARD, alex).outcome());
            assertEquals(2, alex.received.size());
        }

        @Test
        void unknownPlayerOrUnassignedDayIsRejected() {
            FakePlayer alex = new FakePlayer();
            var request = new dev.exodaily.core.claim.ClaimRequest(ALEX, 1, 1, h.clock.today(), RewardPosition.STANDARD);
            assertEquals(ClaimOutcome.STALE, h.claims.claim(request, alex).join().outcome());
            assertTrue(alex.received.isEmpty());
        }
    }

    @Nested
    class Inventory {

        @Test
        void fullInventoryKeepsTheRewardAvailable() {
            FakePlayer alex = new FakePlayer();
            alex.freeSpace = 0;
            DailyView view = h.view(ALEX);
            ClaimResult result = h.claim(view, RewardPosition.STANDARD, alex);
            assertEquals(ClaimOutcome.INVENTORY_FULL, result.outcome());
            assertEquals(null, claimRow(view, RewardPosition.STANDARD));
            assertEquals(PositionStatus.AVAILABLE, h.view(ALEX).status(RewardPosition.STANDARD, false));
            alex.freeSpace = 10_000;
            assertEquals(ClaimOutcome.SUCCESS, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertEquals(1, alex.received.size());
        }

        @Test
        void inventoryFillingUpBetweenCheckAndDeliveryGivesNothingAndReleases() {
            FakePlayer alex = new FakePlayer();
            alex.afterCheck = () -> alex.freeSpace = 0;
            DailyView view = h.view(ALEX);
            assertEquals(ClaimOutcome.INVENTORY_FULL, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertTrue(alex.received.isEmpty());
            assertEquals(null, claimRow(view, RewardPosition.STANDARD));
        }

        @Test
        void unbuildableItemStaysAvailable() {
            FakePlayer alex = new FakePlayer();
            alex.invalidItems = true;
            DailyView view = h.view(ALEX);
            assertEquals(ClaimOutcome.DELIVERY_FAILED, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertEquals(null, claimRow(view, RewardPosition.STANDARD));
        }

        @Test
        void playerLeavingMidClaimGetsNothingAndKeepsTheReward() {
            FakePlayer alex = new FakePlayer();
            alex.afterCheck = () -> alex.online = false;
            DailyView view = h.view(ALEX);
            assertEquals(ClaimOutcome.OFFLINE, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertEquals(null, claimRow(view, RewardPosition.STANDARD));
            alex.afterCheck = () -> { };
            alex.online = true;
            assertEquals(ClaimOutcome.SUCCESS, h.claim(view, RewardPosition.STANDARD, alex).outcome());
        }
    }

    @Nested
    class Concurrency {

        @Test
        void concurrentClaimsOfTheSamePositionDeliverExactlyOnce() throws Exception {
            FakePlayer alex = new FakePlayer();
            DailyView view = h.view(ALEX);
            int attempts = 32;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService clickers = Executors.newFixedThreadPool(8);
            List<CompletableFuture<ClaimResult>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return h.claims.claim(h.request(view, RewardPosition.STANDARD), alex).join();
                }, clickers));
            }
            start.countDown();
            Map<ClaimOutcome, Integer> counts = new EnumMap<>(ClaimOutcome.class);
            for (CompletableFuture<ClaimResult> result : results) {
                counts.merge(result.get(20, TimeUnit.SECONDS).outcome(), 1, Integer::sum);
            }
            clickers.shutdown();
            assertEquals(1, counts.get(ClaimOutcome.SUCCESS), counts.toString());
            assertEquals(1, alex.received.size());
            assertEquals(attempts - 1, counts.getOrDefault(ClaimOutcome.ALREADY_CLAIMED, 0)
                    + counts.getOrDefault(ClaimOutcome.IN_PROGRESS, 0), counts.toString());
        }

        @Test
        void databaseUniquenessHoldsEvenWithParallelStorageThreads(@TempDir Path other) throws Exception {
            try (Harness parallel = new Harness(other, Fixtures.smallCatalog(true), 6)) {
                FakePlayer alex = new FakePlayer().premium(true);
                DailyView view = parallel.view(ALEX);
                List<CompletableFuture<ClaimResult>> results = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    RewardPosition position = RewardPosition.values()[i % 3];
                    results.add(parallel.claims.claim(parallel.request(view, position), alex));
                }
                int successes = 0;
                for (CompletableFuture<ClaimResult> result : results) {
                    if (result.get(20, TimeUnit.SECONDS).outcome() == ClaimOutcome.SUCCESS) {
                        successes++;
                    }
                }
                assertEquals(3, successes);
                assertEquals(3, alex.received.size());
                assertEquals(3, Set3.of(alex.received));
            }
        }

        @Test
        void clickGuardBlocksRepeatedClicksAndOverlappingClaims() {
            ClickGuard guard = new ClickGuard();
            long cooldown = TimeUnit.MILLISECONDS.toNanos(300);
            assertTrue(guard.tryBegin(1_000_000_000L, cooldown));
            // In flight: any further claim is refused, even after the cooldown.
            assertFalse(guard.tryBegin(1_000_000_000L + cooldown * 10, cooldown));
            guard.end();
            // Cooldown measured from the last accepted click.
            assertFalse(guard.tryBegin(1_000_000_000L + cooldown / 2, cooldown));
            assertTrue(guard.tryBegin(1_000_000_000L + cooldown, cooldown));
            guard.end();
            assertFalse(guard.tryClick(1_000_000_000L + cooldown + 1, cooldown));
        }
    }

    @Nested
    class FailuresAndRecovery {

        @Test
        void unavailableStorageFailsClosed() {
            FakePlayer alex = new FakePlayer();
            DailyView view = h.view(ALEX);
            h.store.close();
            assertEquals(ClaimOutcome.STORAGE_ERROR, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertTrue(alex.received.isEmpty());
        }

        @Test
        void crashBeforeDeliveryIsReleasedOnRestart() {
            FakePlayer alex = new FakePlayer();
            DailyView view = h.view(ALEX);
            // The database becomes unavailable right after the reservation: nothing may be given.
            alex.afterCheck = () -> h.store.close();
            assertEquals(ClaimOutcome.STORAGE_ERROR, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertTrue(alex.received.isEmpty());

            AdminService.RecoveryReport report = h.openStore();
            assertEquals(1, report.released());
            assertEquals(0, report.flaggedUncertain());
            alex.afterCheck = () -> { };
            assertEquals(ClaimOutcome.SUCCESS, h.claim(h.view(ALEX), RewardPosition.STANDARD, alex).outcome());
            assertEquals(1, alex.received.size());
        }

        @Test
        void crashAfterDeliveryIsFlaggedAndNeverReissued() throws Exception {
            FakePlayer alex = new FakePlayer();
            DailyView view = h.view(ALEX);
            // Items are given, then the database dies before DELIVERED is recorded.
            alex.afterDeliver = () -> h.store.close();
            assertEquals(ClaimOutcome.DELIVERED_UNCONFIRMED, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertEquals(1, alex.received.size());

            AdminService.RecoveryReport report = h.openStore();
            assertEquals(1, report.flaggedUncertain());
            assertEquals(1, report.totalUncertain());
            alex.afterDeliver = () -> { };

            DailyView reopened = h.view(ALEX);
            assertEquals(PositionStatus.REVIEW, reopened.status(RewardPosition.STANDARD, false));
            assertEquals(ClaimOutcome.PENDING_REVIEW, h.claim(reopened, RewardPosition.STANDARD, alex).outcome());
            assertEquals(1, alex.received.size(), "an uncertain delivery must never be reissued automatically");

            List<ClaimRecord> pending = h.admin.uncertain(10);
            assertEquals(1, pending.size());
            assertEquals(ClaimState.UNCERTAIN, pending.getFirst().state());
            assertTrue(h.admin.resolve(pending.getFirst().id(), true, "test-admin").isPresent());
            assertEquals(ClaimOutcome.ALREADY_CLAIMED, h.claim(h.view(ALEX), RewardPosition.STANDARD, alex).outcome());
            assertEquals(1, alex.received.size());
            assertTrue(Files.readString(dir.resolve("audit.log")).contains("resolve-delivered"));
        }

        @Test
        void uncertainDeliveryIsHeldForReview() {
            FakePlayer alex = new FakePlayer();
            alex.deliveryUncertain = true;
            DailyView view = h.view(ALEX);
            assertEquals(ClaimOutcome.PENDING_REVIEW, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            assertEquals(ClaimState.UNCERTAIN, claimRow(view, RewardPosition.STANDARD).state());
            assertEquals(ClaimOutcome.PENDING_REVIEW, h.claim(view, RewardPosition.STANDARD, alex).outcome());
        }

        @Test
        void releasingAnUncertainClaimMakesItClaimableAgainTheSameDay() {
            FakePlayer alex = new FakePlayer();
            alex.deliveryUncertain = true;
            DailyView view = h.view(ALEX);
            h.claim(view, RewardPosition.STANDARD, alex);
            long id = claimRow(view, RewardPosition.STANDARD).id();
            assertTrue(h.admin.resolve(id, false, "test-admin").isPresent());
            alex.deliveryUncertain = false;
            assertEquals(ClaimOutcome.SUCCESS, h.claim(h.view(ALEX), RewardPosition.STANDARD, alex).outcome());
            assertTrue(h.admin.resolve(id, false, "test-admin").isEmpty(), "only uncertain claims can be resolved");
        }

        @Test
        void claimsSurviveRestarts() {
            FakePlayer alex = new FakePlayer();
            DailyView view = h.view(ALEX);
            assertEquals(ClaimOutcome.SUCCESS, h.claim(view, RewardPosition.STANDARD, alex).outcome());
            AdminService.RecoveryReport report = h.restart();
            assertEquals(0, report.released());
            assertEquals(0, report.flaggedUncertain());
            assertEquals(ClaimOutcome.ALREADY_CLAIMED, h.claim(h.view(ALEX), RewardPosition.STANDARD, alex).outcome());
            assertEquals(1, alex.received.size());
        }
    }
}
