package dev.exodaily.core.service;

import dev.exodaily.core.audit.AuditLog;
import dev.exodaily.core.progression.CycleState;
import dev.exodaily.core.progression.PlayerProfile;
import dev.exodaily.core.progression.Progression;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.storage.AssignmentRecord;
import dev.exodaily.core.storage.AuditEntry;
import dev.exodaily.core.storage.ClaimRecord;
import dev.exodaily.core.storage.ClaimState;
import dev.exodaily.core.storage.SqliteStore;
import dev.exodaily.core.time.DailyClock;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Administrative operations. All run on the storage thread, each in one transaction, and each
 * mutation writes an audit record.
 *
 * <ul>
 *     <li>{@code setday} preserves all claims and assignments: it moves the cycle start date
 *     within the same cycle, so a (cycle, day, position) claimed before stays claimed.</li>
 *     <li>{@code reset} starts a new cycle at day 1 today. Old claims are kept for history but
 *     no longer block anything, so the player CAN earn rewards again, including today.</li>
 *     <li>{@code resolve} settles an UNCERTAIN delivery: {@code delivered} keeps it blocked,
 *     {@code release} deletes the claim so the position can be claimed again while its day is
 *     still the player's current day.</li>
 * </ul>
 */
public final class AdminService {

    private final SqliteStore store;
    private final DailyClock clock;
    private final IntSupplier cycleLength;
    private final AuditLog auditLog;

    public AdminService(SqliteStore store, DailyClock clock, IntSupplier cycleLength, AuditLog auditLog) {
        this.store = store;
        this.clock = clock;
        this.cycleLength = cycleLength;
        this.auditLog = auditLog;
    }

    public record Status(
            UUID uuid,
            String lastName,
            PlayerProfile profile,
            CycleState state,
            Map<RewardPosition, AssignmentRecord> assignments,
            Map<RewardPosition, ClaimRecord> claims,
            List<ClaimRecord> uncertain
    ) {
    }

    public record RecoveryReport(int released, int flaggedUncertain, int totalUncertain) {
    }

    public Optional<UUID> findByName(String name) {
        return store.transaction(tx -> tx.findByName(name));
    }

    /** Read-only: never creates a profile or assigns rewards. */
    public Optional<Status> status(UUID uuid) {
        LocalDate today = clock.today();
        int configuredLength = cycleLength.getAsInt();
        return store.transaction(tx -> {
            Optional<PlayerProfile> profile = tx.profile(uuid);
            if (profile.isEmpty()) {
                return Optional.empty();
            }
            Progression.Resolution resolution = Progression.resolve(profile.get(), today, configuredLength);
            CycleState state = resolution.state();
            return Optional.of(new Status(uuid, tx.lastName(uuid).orElse(null), resolution.profile(), state,
                    tx.assignments(uuid, state.cycleNumber(), state.day()),
                    tx.claims(uuid, state.cycleNumber(), state.day()),
                    tx.claimsInState(ClaimState.UNCERTAIN, uuid, 20)));
        });
    }

    /** Sets the current day, preserving every claim. Creates the profile if the player never used /daily. */
    public PlayerProfile setDay(UUID uuid, String name, int day, String actor) {
        LocalDate today = clock.today();
        long now = clock.instant().toEpochMilli();
        int configuredLength = cycleLength.getAsInt();
        AuditEntry[] audit = new AuditEntry[1];
        PlayerProfile result = store.transaction(tx -> {
            PlayerProfile profile = tx.profile(uuid).orElseGet(() -> Progression.startNew(uuid, today, configuredLength));
            PlayerProfile updated = Progression.setDay(profile, today, day, configuredLength);
            tx.saveProfile(updated, name, now);
            audit[0] = new AuditEntry(now, actor, "setday", target(uuid, name),
                    "day=" + day + " cycle=" + updated.cycleNumber() + " cycleStart=" + updated.cycleStart()
                            + " (claims preserved)");
            tx.audit(audit[0]);
            return updated;
        });
        auditLog.append(audit[0]);
        return result;
    }

    /** Starts a fresh cycle at day 1 today; rewards can be earned again. Empty if the player has no data. */
    public Optional<PlayerProfile> reset(UUID uuid, String name, String actor) {
        LocalDate today = clock.today();
        long now = clock.instant().toEpochMilli();
        int configuredLength = cycleLength.getAsInt();
        AuditEntry[] audit = new AuditEntry[1];
        Optional<PlayerProfile> result = store.transaction(tx -> {
            Optional<PlayerProfile> profile = tx.profile(uuid);
            if (profile.isEmpty()) {
                return Optional.<PlayerProfile>empty();
            }
            PlayerProfile updated = Progression.reset(profile.get(), today, configuredLength);
            tx.saveProfile(updated, name, now);
            audit[0] = new AuditEntry(now, actor, "reset", target(uuid, name),
                    "new cycle=" + updated.cycleNumber() + " start=" + today
                            + " (previous claims kept as history; rewards can be earned again)");
            tx.audit(audit[0]);
            return Optional.of(updated);
        });
        if (audit[0] != null) {
            auditLog.append(audit[0]);
        }
        return result;
    }

    public List<ClaimRecord> uncertain(int limit) {
        return store.transaction(tx -> tx.claimsInState(ClaimState.UNCERTAIN, null, limit));
    }

    public int uncertainCount() {
        return store.transaction(tx -> tx.countInState(ClaimState.UNCERTAIN));
    }

    /** Resolves an UNCERTAIN claim; empty if no uncertain claim has this id. */
    public Optional<ClaimRecord> resolve(long claimId, boolean delivered, String actor) {
        long now = clock.instant().toEpochMilli();
        AuditEntry[] audit = new AuditEntry[1];
        Optional<ClaimRecord> result = store.transaction(tx -> {
            Optional<ClaimRecord> claim = tx.claimById(claimId);
            if (claim.isEmpty() || claim.get().state() != ClaimState.UNCERTAIN) {
                return Optional.<ClaimRecord>empty();
            }
            if (!tx.resolveUncertain(claimId, delivered, now, "resolved by " + actor)) {
                return Optional.<ClaimRecord>empty();
            }
            audit[0] = new AuditEntry(now, actor, delivered ? "resolve-delivered" : "resolve-release",
                    claim.get().uuid().toString(), "claim=" + claimId + " key=" + claim.get().key().asString()
                    + " reward=" + claim.get().rewardId());
            tx.audit(audit[0]);
            return claim;
        });
        if (audit[0] != null) {
            auditLog.append(audit[0]);
        }
        return result;
    }

    /** Records a non-storage administrative action (reload, reward save). */
    public void audit(String actor, String action, String target, String details) {
        AuditEntry entry = new AuditEntry(clock.instant().toEpochMilli(), actor, action, target, details);
        store.transaction(tx -> {
            tx.audit(entry);
            return null;
        });
        auditLog.append(entry);
    }

    /**
     * Startup recovery policy. RESERVED claims never reached the inventory change, so they are
     * deleted and the reward becomes claimable again (if its day is still current). DELIVERING
     * claims may or may not have been delivered, so they become UNCERTAIN and stay blocked until
     * an administrator resolves them.
     */
    public RecoveryReport recover() {
        long now = clock.instant().toEpochMilli();
        return store.transaction(tx -> {
            int released = tx.deleteAllReserved();
            int flagged = tx.flagDeliveringUncertain("interrupted during delivery (flagged at startup " + now + ")");
            if (released > 0 || flagged > 0) {
                tx.audit(new AuditEntry(now, "system", "recovery", "*",
                        "released=" + released + " flaggedUncertain=" + flagged));
            }
            return new RecoveryReport(released, flagged, tx.countInState(ClaimState.UNCERTAIN));
        });
    }

    private static String target(UUID uuid, String name) {
        return name == null ? uuid.toString() : name + " (" + uuid + ")";
    }
}
