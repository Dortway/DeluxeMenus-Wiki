package dev.exodaily.support;

import dev.exodaily.core.audit.AuditLog;
import dev.exodaily.core.claim.ClaimRequest;
import dev.exodaily.core.claim.ClaimResult;
import dev.exodaily.core.claim.ClaimService;
import dev.exodaily.core.progression.CycleState;
import dev.exodaily.core.reward.RewardCatalog;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.service.AdminService;
import dev.exodaily.core.service.DailyService;
import dev.exodaily.core.service.DailyView;
import dev.exodaily.core.storage.SqliteStore;
import dev.exodaily.core.time.DailyClock;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Real SQLite storage and real services, with two executors standing in for the storage
 * thread and the server thread.
 */
public final class Harness implements AutoCloseable {

    public static final ZoneId ZONE = ZoneId.of("Europe/London");
    public static final Logger LOGGER = Logger.getLogger("ExoDailyTest");

    public final Path dir;
    public final MutableClock source;
    public final DailyClock clock;
    public final AtomicReference<RewardCatalog> catalog;
    public volatile int cycleLength = 30;
    public final ExecutorService storageExecutor;
    public final ExecutorService serverExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-server"));
    public SqliteStore store;
    public DailyService daily;
    public ClaimService claims;
    public AdminService admin;
    private final SplittableRandom seeds = new SplittableRandom(1234);

    public Harness(Path dir, RewardCatalog catalog) {
        this(dir, catalog, 1);
    }

    public Harness(Path dir, RewardCatalog catalog, int storageThreads) {
        this.dir = dir;
        this.source = new MutableClock(at("2026-01-01T12:00:00"));
        this.clock = new DailyClock(source, ZONE);
        this.catalog = new AtomicReference<>(catalog);
        this.storageExecutor = storageThreads == 1
                ? Executors.newSingleThreadExecutor(r -> new Thread(r, "test-storage"))
                : Executors.newFixedThreadPool(storageThreads);
        openStore();
    }

    public static Instant at(String localDateTime) {
        return ZonedDateTime.of(LocalDateTime.parse(localDateTime), ZONE).toInstant();
    }

    /** (Re)opens the database file and runs startup recovery, like a server start. */
    public AdminService.RecoveryReport openStore() {
        store = new SqliteStore(dir.resolve("data.db"), 5000, LOGGER);
        store.open();
        daily = new DailyService(store, clock, catalog::get, () -> cycleLength,
                () -> seeds.split(), LOGGER);
        claims = new ClaimService(store, storageExecutor, serverExecutor, clock, () -> cycleLength, LOGGER);
        admin = new AdminService(store, clock, () -> cycleLength, new AuditLog(dir.resolve("audit.log"), true, LOGGER));
        return admin.recover();
    }

    /** Simulates a restart: closes and reopens the database (with recovery). */
    public AdminService.RecoveryReport restart() {
        store.close();
        return openStore();
    }

    public DailyView view(UUID uuid) {
        return daily.open(uuid, "player-" + uuid.toString().substring(0, 4));
    }

    public ClaimRequest request(DailyView view, RewardPosition position) {
        CycleState state = view.state();
        return new ClaimRequest(view.profile().uuid(), state.cycleNumber(), state.day(), state.date(), position);
    }

    public ClaimResult claim(DailyView view, RewardPosition position, FakePlayer player) {
        try {
            return claims.claim(request(view, position), player).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("claim did not complete", e);
        }
    }

    @Override
    public void close() {
        storageExecutor.shutdown();
        serverExecutor.shutdown();
        try {
            storageExecutor.awaitTermination(10, TimeUnit.SECONDS);
            serverExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        store.close();
    }
}
