package dev.exodaily.core.claim;

import dev.exodaily.core.progression.PlayerProfile;
import dev.exodaily.core.progression.Progression;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.storage.AssignmentRecord;
import dev.exodaily.core.storage.ClaimKey;
import dev.exodaily.core.storage.ClaimRecord;
import dev.exodaily.core.storage.ClaimState;
import dev.exodaily.core.storage.SqliteStore;
import dev.exodaily.core.time.DailyClock;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The claim pipeline. Storage steps run on the single storage executor (which serializes
 * competing claims), player steps run on the server thread:
 *
 * <pre>
 *  storage: revalidate date/cycle/day/assignment, INSERT claim as RESERVED (UNIQUE key)
 *  server : online? premium (live)? item buildable? fits completely?   -- no: delete reservation
 *  storage: RESERVED -> DELIVERING (durable before any inventory change)
 *  server : re-check, add items all-or-nothing                          -- no space: delete, nothing given
 *  storage: DELIVERING -> DELIVERED
 * </pre>
 *
 * Nothing is ever given before the reservation is committed, and any storage failure before
 * delivery fails closed. A claim left in DELIVERING by a crash is flagged UNCERTAIN on the next
 * start and never reissued automatically.
 */
public final class ClaimService {

    private final SqliteStore store;
    private final Executor storage;
    private final Executor server;
    private final DailyClock clock;
    private final IntSupplier cycleLength;
    private final Logger logger;

    public ClaimService(SqliteStore store, Executor storage, Executor server, DailyClock clock,
                        IntSupplier cycleLength, Logger logger) {
        this.store = store;
        this.storage = storage;
        this.server = server;
        this.clock = clock;
        this.cycleLength = cycleLength;
        this.logger = logger;
    }

    private record Reservation(ClaimOutcome failure, ClaimRecord existing, RewardDefinition reward) {

        static Reservation fail(ClaimOutcome outcome) {
            return new Reservation(outcome, null, null);
        }

        static Reservation fail(ClaimOutcome outcome, ClaimRecord existing) {
            return new Reservation(outcome, existing, null);
        }

        boolean ok() {
            return failure == null;
        }
    }

    public CompletableFuture<ClaimResult> claim(ClaimRequest request, ClaimParticipant participant) {
        String attempt = UUID.randomUUID().toString();
        return on(storage, () -> reserve(request, attempt))
                .handle((reservation, error) -> {
                    if (error == null) {
                        return reservation;
                    }
                    Throwable cause = unwrap(error);
                    if (cause instanceof RejectedExecutionException) {
                        return Reservation.fail(ClaimOutcome.BUSY);
                    }
                    logger.log(Level.SEVERE, "Could not reserve claim " + request.key() + "; nothing was delivered", cause);
                    return Reservation.fail(ClaimOutcome.STORAGE_ERROR);
                })
                .thenCompose(reservation -> reservation.ok()
                        ? afterReservation(request, participant, attempt, reservation.reward())
                        : CompletableFuture.completedFuture(new ClaimResult(reservation.failure(), null, reservation.existing())));
    }

    // ------------------------------------------------------------------ step 1: reserve (storage)

    private Reservation reserve(ClaimRequest request, String attempt) {
        LocalDate today = clock.today();
        long now = clock.instant().toEpochMilli();
        int configuredLength = cycleLength.getAsInt();
        ClaimKey key = request.key();
        return store.transaction(tx -> {
            Optional<PlayerProfile> profile = tx.profile(request.uuid());
            if (profile.isEmpty()) {
                return Reservation.fail(ClaimOutcome.STALE);
            }
            Progression.Resolution resolution = Progression.resolve(profile.get(), today, configuredLength);
            if (resolution.changed()) {
                tx.saveProfile(resolution.profile(), null, now);
            }
            // The authoritative date is read here, on the storage thread, at reservation time. A
            // menu rendered before midnight cannot claim after midnight, and past days cannot be
            // claimed: missed days are skipped permanently.
            if (resolution.state().cycleNumber() != request.cycle()
                    || resolution.state().day() != request.day()
                    || !today.equals(request.date())) {
                return Reservation.fail(ClaimOutcome.STALE);
            }
            Optional<ClaimRecord> existing = tx.claim(key);
            if (existing.isPresent()) {
                return Reservation.fail(outcomeFor(existing.get().state()), existing.get());
            }
            AssignmentRecord assignment = tx.assignments(request.uuid(), request.cycle(), request.day()).get(request.position());
            if (assignment == null) {
                return Reservation.fail(ClaimOutcome.NOT_ASSIGNED);
            }
            if (!tx.insertReservation(key, today, assignment.reward().id(), attempt, now)) {
                ClaimRecord winner = tx.claim(key).orElse(null);
                return Reservation.fail(winner == null ? ClaimOutcome.IN_PROGRESS : outcomeFor(winner.state()), winner);
            }
            return new Reservation(null, null, assignment.reward());
        });
    }

    private static ClaimOutcome outcomeFor(ClaimState state) {
        return switch (state) {
            case DELIVERED -> ClaimOutcome.ALREADY_CLAIMED;
            case UNCERTAIN -> ClaimOutcome.PENDING_REVIEW;
            case RESERVED, DELIVERING -> ClaimOutcome.IN_PROGRESS;
        };
    }

    // ------------------------------------------------------------------ step 2: preflight (server)

    private CompletableFuture<ClaimResult> afterReservation(ClaimRequest request, ClaimParticipant participant,
                                                            String attempt, RewardDefinition reward) {
        return on(server, () -> preflight(request, participant, reward))
                .handle((outcome, error) -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "Claim preflight failed for " + request.key(), unwrap(error));
                        return ClaimOutcome.OFFLINE;
                    }
                    return outcome;
                })
                .thenCompose(outcome -> outcome != null
                        ? release(request.key(), attempt, ClaimState.RESERVED, reward, outcome)
                        : markDelivering(request, participant, attempt, reward));
    }

    /** Returns null when delivery may proceed, otherwise the reason it may not. Nothing is given. */
    private ClaimOutcome preflight(ClaimRequest request, ClaimParticipant participant, RewardDefinition reward) {
        if (!participant.isOnline()) {
            return ClaimOutcome.OFFLINE;
        }
        if (request.position().requiresPremium() && !participant.hasPremium()) {
            return ClaimOutcome.LOCKED;
        }
        ClaimParticipant.DeliveryCheck check;
        try {
            check = participant.check(reward);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not prepare reward '" + reward.id() + "' for " + request.key(), e);
            return ClaimOutcome.DELIVERY_FAILED;
        }
        return switch (check) {
            case OK -> null;
            case NO_SPACE -> ClaimOutcome.INVENTORY_FULL;
            case INVALID_ITEM -> ClaimOutcome.DELIVERY_FAILED;
        };
    }

    // ------------------------------------------------------------------ step 3: mark delivering (storage)

    private CompletableFuture<ClaimResult> markDelivering(ClaimRequest request, ClaimParticipant participant,
                                                          String attempt, RewardDefinition reward) {
        ClaimKey key = request.key();
        return on(storage, () -> store.transaction(tx ->
                tx.transition(key, attempt, ClaimState.RESERVED, ClaimState.DELIVERING, clock.instant().toEpochMilli(), null)))
                .handle((moved, error) -> {
                    if (error != null) {
                        logger.log(Level.SEVERE, "Could not mark claim " + key + " as delivering; nothing was delivered", unwrap(error));
                        return Boolean.FALSE;
                    }
                    return moved;
                })
                .thenCompose(moved -> moved
                        ? deliver(request, participant, attempt, reward)
                        : release(key, attempt, ClaimState.RESERVED, reward, ClaimOutcome.STORAGE_ERROR));
    }

    // ------------------------------------------------------------------ step 4: deliver (server)

    private CompletableFuture<ClaimResult> deliver(ClaimRequest request, ClaimParticipant participant,
                                                   String attempt, RewardDefinition reward) {
        ClaimKey key = request.key();
        return on(server, () -> {
            if (!participant.isOnline()) {
                return ClaimOutcome.OFFLINE;
            }
            if (request.position().requiresPremium() && !participant.hasPremium()) {
                return ClaimOutcome.LOCKED;
            }
            ClaimParticipant.DeliveryResult result;
            try {
                result = participant.deliver(reward);
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Unexpected error while delivering " + key + "; delivery is uncertain", e);
                result = ClaimParticipant.DeliveryResult.UNCERTAIN;
            }
            return switch (result) {
                case DELIVERED -> ClaimOutcome.SUCCESS;
                case NOT_DELIVERED_NO_SPACE -> ClaimOutcome.INVENTORY_FULL;
                case NOT_DELIVERED_INVALID -> ClaimOutcome.DELIVERY_FAILED;
                case UNCERTAIN -> ClaimOutcome.PENDING_REVIEW;
            };
        }).handle((outcome, error) -> {
            if (error != null) {
                // The task never ran (server shutting down): nothing was given.
                logger.log(Level.WARNING, "Delivery task for " + key + " did not run", unwrap(error));
                return ClaimOutcome.OFFLINE;
            }
            return outcome;
        }).thenCompose(outcome -> switch (outcome) {
            case SUCCESS -> markDelivered(key, attempt, reward);
            case PENDING_REVIEW -> markUncertain(key, attempt, reward);
            default -> release(key, attempt, ClaimState.DELIVERING, reward, outcome);
        });
    }

    // ------------------------------------------------------------------ step 5: finalize (storage)

    private CompletableFuture<ClaimResult> markDelivered(ClaimKey key, String attempt, RewardDefinition reward) {
        return on(storage, () -> store.transaction(tx -> {
            boolean moved = tx.transition(key, attempt, ClaimState.DELIVERING, ClaimState.DELIVERED,
                    clock.instant().toEpochMilli(), null);
            if (!moved) {
                throw new IllegalStateException("claim " + key + " was not in DELIVERING state for attempt " + attempt);
            }
            return tx.claim(key).orElse(null);
        })).handle((claim, error) -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Reward for claim " + key + " WAS delivered but recording it failed. The claim stays"
                        + " blocked and will be flagged UNCERTAIN for review on the next start.", unwrap(error));
                return new ClaimResult(ClaimOutcome.DELIVERED_UNCONFIRMED, reward, null);
            }
            return new ClaimResult(ClaimOutcome.SUCCESS, reward, claim);
        });
    }

    private CompletableFuture<ClaimResult> markUncertain(ClaimKey key, String attempt, RewardDefinition reward) {
        return on(storage, () -> store.transaction(tx -> {
            tx.transition(key, attempt, ClaimState.DELIVERING, ClaimState.UNCERTAIN, clock.instant().toEpochMilli(),
                    "delivery raised an unexpected error");
            return tx.claim(key).orElse(null);
        })).handle((claim, error) -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Could not flag claim " + key + " as uncertain; it stays blocked until restart", unwrap(error));
            }
            return new ClaimResult(ClaimOutcome.PENDING_REVIEW, reward, claim);
        });
    }

    /** Removes a reservation that provably gave nothing, so the reward stays available. */
    private CompletableFuture<ClaimResult> release(ClaimKey key, String attempt, ClaimState from,
                                                   RewardDefinition reward, ClaimOutcome outcome) {
        return on(storage, () -> store.transaction(tx -> tx.delete(key, attempt, from)))
                .handle((deleted, error) -> {
                    if (error != null) {
                        logger.log(Level.SEVERE, "Could not release claim " + key + " in state " + from
                                + " (nothing was delivered). " + (from == ClaimState.RESERVED
                                ? "It will be released automatically on the next start."
                                : "It will be flagged for review on the next start; resolve it with /exodaily resolve <id> release."),
                                unwrap(error));
                    }
                    return new ClaimResult(outcome, reward, null);
                });
    }

    // ------------------------------------------------------------------ helpers

    private static <T> CompletableFuture<T> on(Executor executor, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
