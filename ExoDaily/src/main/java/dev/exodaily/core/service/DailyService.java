package dev.exodaily.core.service;

import dev.exodaily.core.progression.CycleState;
import dev.exodaily.core.progression.PlayerProfile;
import dev.exodaily.core.progression.Progression;
import dev.exodaily.core.reward.RewardCatalog;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.reward.RewardSelector;
import dev.exodaily.core.storage.AssignmentRecord;
import dev.exodaily.core.storage.ClaimRecord;
import dev.exodaily.core.storage.SqliteStore;
import dev.exodaily.core.time.DailyClock;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * Builds a player's {@link DailyView}. Must run on the storage thread. Assigns and persists all
 * three positions the first time a day is viewed; existing assignments are never rerolled.
 */
public final class DailyService {

    private final SqliteStore store;
    private final DailyClock clock;
    private final Supplier<RewardCatalog> catalog;
    private final IntSupplier cycleLength;
    private final Supplier<RandomGenerator> random;
    private final Logger logger;
    /** Duplicate-fallback warnings already logged, so a thin pool does not flood the console. */
    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DailyService(SqliteStore store, DailyClock clock, Supplier<RewardCatalog> catalog, IntSupplier cycleLength,
                        Supplier<RandomGenerator> random, Logger logger) {
        this.store = store;
        this.clock = clock;
        this.catalog = catalog;
        this.cycleLength = cycleLength;
        this.random = random;
        this.logger = logger;
    }

    /** Opens (and if needed creates) the player's view for today. */
    public DailyView open(UUID uuid, String name) {
        LocalDate today = clock.today();
        long now = clock.instant().toEpochMilli();
        int configuredLength = cycleLength.getAsInt();
        RewardCatalog rewards = catalog.get();
        return store.transaction(tx -> {
            Optional<PlayerProfile> existing = tx.profile(uuid);
            PlayerProfile profile = existing.orElseGet(() -> Progression.startNew(uuid, today, configuredLength));
            Progression.Resolution resolution = Progression.resolve(profile, today, configuredLength);
            if (existing.isEmpty() || resolution.changed() || name != null) {
                tx.saveProfile(resolution.profile(), name, now);
            }
            CycleState state = resolution.state();
            Map<RewardPosition, AssignmentRecord> assignments = tx.assignments(uuid, state.cycleNumber(), state.day());
            if (assignments.size() < RewardPosition.values().length) {
                Map<RewardPosition, RewardSelector.Selection> selections =
                        RewardSelector.select(rewards, state.day(), random.get());
                for (RewardSelector.Selection selection : selections.values()) {
                    if (assignments.containsKey(selection.position())) {
                        continue;
                    }
                    if (selection.duplicateAllowed()
                            && warned.add(state.day() + ":" + selection.position() + ":" + selection.poolId())) {
                        logger.warning("Not enough unique rewards for day " + state.day() + " position "
                                + selection.position().number() + " (pool '" + selection.poolId()
                                + "'); a duplicate reward was assigned. Add more entries or a fallback pool in rewards.yml."
                                + " (Logged once per day and position.)");
                    }
                    tx.insertAssignment(new AssignmentRecord(uuid, state.cycleNumber(), state.day(), selection.position(),
                            today, selection.poolId(), selection.reward(), now));
                }
                assignments = tx.assignments(uuid, state.cycleNumber(), state.day());
            }
            Map<RewardPosition, ClaimRecord> claims = tx.claims(uuid, state.cycleNumber(), state.day());
            PreviousDay previous = previousDay(tx, resolution.profile(), today);
            Map<Integer, Integer> counts = tx.claimCountsByDay(uuid, state.cycleNumber());
            return new DailyView(resolution.profile(), state, assignments, claims, previous, counts);
        });
    }

    static PreviousDay previousDay(SqliteStore.Tx tx, PlayerProfile profile, LocalDate today) throws java.sql.SQLException {
        LocalDate yesterday = today.minusDays(1);
        if (profile.firstUseDate().isAfter(yesterday)) {
            return new PreviousDay(PreviousDay.Kind.NONE, yesterday, 0);
        }
        int claimed = tx.countClaimsOnDate(profile.uuid(), yesterday);
        return new PreviousDay(claimed > 0 ? PreviousDay.Kind.CLAIMED : PreviousDay.Kind.MISSED, yesterday, claimed);
    }
}
