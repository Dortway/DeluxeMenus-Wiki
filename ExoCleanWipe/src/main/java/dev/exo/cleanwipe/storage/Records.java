package dev.exo.cleanwipe.storage;

import java.util.UUID;

/** Plain records stored in ExoCleanWipe's own database ({@code plugins/ExoCleanWipe/exocleanwipe.db}). */
public final class Records {

    private Records() {
    }

    public record Account(UUID uuid, String name, long firstSeen, long lastSeen) {
    }

    public record NameEntry(String name, long firstSeen, long lastSeen) {
    }

    public record IpEntry(UUID uuid, String ip, long firstSeen, long lastSeen, int logins) {
    }

    public enum PunishmentKind { ACCOUNT, IP_GROUP }

    /**
     * @param expiresAt epoch millis, or {@code null} for permanent
     * @param active    false once removed by an unpunish command
     */
    public record Punishment(long id, PunishmentKind kind, UUID uuid, String name, String reason, String actorName,
                             long createdAt, Long expiresAt, Long jobId, boolean active) {

        public boolean permanent() {
            return expiresAt == null;
        }

        public boolean inForce(long now) {
            return active && (expiresAt == null || expiresAt > now);
        }
    }

    public enum JobType { WIPE, PUNISH, WIPE_ALL, RESTORE }

    public enum JobState { RUNNING, WAITING_RESTART, COMPLETED, PARTIAL, CANCELLED }

    public record Job(long id, JobType type, JobState state, String actorName, String reason, String options,
                      long createdAt, long updatedAt, Long punishmentId, String note) {
    }

    public enum Phase { LIVE, STARTUP }

    /**
     * PENDING: not run yet. DONE: data removed and backed up. NOTHING: integration had nothing stored for the
     * target. SKIPPED: deliberately not run (disabled in config, not installed, staff kept a shared island).
     * BLOCKED: refused because running it could affect other players (for example an unfinished transaction).
     * FAILED: an error happened; the account stays locked.
     */
    public enum StepState { PENDING, DONE, NOTHING, SKIPPED, BLOCKED, FAILED,
        /** Only returned by live integrations: could not be done safely now; the step is moved to the next start. */
        DEFERRED;

        public boolean finished() {
            return this != PENDING && this != DEFERRED;
        }

        public boolean problem() {
            return this == BLOCKED || this == FAILED;
        }
    }

    /** {@code uuid} is {@code null} for a step that covers every player (season reset). */
    public record Step(long jobId, UUID uuid, String integration, Phase phase, StepState state, String detail,
                       int attempts, long updatedAt) {
    }

    public record Target(long jobId, UUID uuid, String name, String role) {
    }

    public record Lock(UUID uuid, long jobId, String reason, long createdAt) {
    }
}
