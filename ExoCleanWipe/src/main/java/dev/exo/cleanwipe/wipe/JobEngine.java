package dev.exo.cleanwipe.wipe;

import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.storage.Records.Job;
import dev.exo.cleanwipe.storage.Records.JobState;
import dev.exo.cleanwipe.storage.Records.JobType;
import dev.exo.cleanwipe.storage.Records.Phase;
import dev.exo.cleanwipe.storage.Records.Step;
import dev.exo.cleanwipe.storage.Records.StepState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent wipe jobs. A job is written (with its targets, every step and the account locks) in one transaction
 * before anything is changed, so a crash at any point leaves a job that resumes: live steps on the next enable,
 * startup steps in the next {@code onLoad}. Steps are idempotent and keep their first backup.
 *
 * <p>A job only releases its locks when every step finished without a problem. BLOCKED or FAILED steps leave the job
 * PARTIAL and the accounts locked until staff retry it or unlock them explicitly.
 */
public final class JobEngine {

    public record Target(UUID uuid, String name, String role) {
    }

    public record StepPlan(UUID uuid, String integration, Phase phase) {
    }

    /** Extra work inside the job transaction (creating the punishment of a /wipe punish). Returns a punishment id or null. */
    public interface InTransaction {
        Long run(Connection c, long jobId) throws SQLException;
    }

    private final CoreStore store;
    private final IntegrationRegistry registry;
    private final Path backupRoot;
    private final Logger logger;
    private Consumer<String> notifier = s -> { };

    public JobEngine(CoreStore store, IntegrationRegistry registry, Path backupRoot, Logger logger) {
        this.store = store;
        this.registry = registry;
        this.backupRoot = backupRoot;
        this.logger = logger;
    }

    public void setNotifier(Consumer<String> notifier) {
        this.notifier = notifier;
    }

    public CoreStore store() {
        return store;
    }

    public IntegrationRegistry registry() {
        return registry;
    }

    public Path backupDir(long jobId, UUID uuid, String integration) {
        return backupRoot.resolve("job-" + jobId).resolve(uuid == null ? "all" : uuid.toString()).resolve(integration);
    }

    public long create(JobType type, UUID actor, String actorName, String reason, WipeOptions options, List<Target> targets,
                       List<StepPlan> steps, boolean globalLock, InTransaction extra) throws SQLException {
        long now = System.currentTimeMillis();
        return store.transaction(c -> {
            long id = store.insertJob(c, type, actor, actorName, reason, options.encode(), now);
            for (Target t : targets) {
                store.insertTarget(c, id, t.uuid(), t.name(), t.role());
                store.insertLock(c, t.uuid(), id, type.name().toLowerCase() + " job " + id, now);
            }
            if (globalLock) {
                store.insertLock(c, null, id, "season reset job " + id, now);
            }
            for (StepPlan s : steps) {
                store.insertStep(c, id, s.uuid(), s.integration(), s.phase(), now);
            }
            if (extra != null) {
                Long punishment = extra.run(c, id);
                if (punishment != null) {
                    store.setJobPunishment(c, id, punishment);
                }
            }
            return id;
        });
    }

    /** Runs every pending startup step. Called from onLoad, before the owning plugins open their data. */
    public int runStartupSteps(boolean pluginsAvailable) {
        List<Step> pending;
        try {
            pending = store.pendingSteps(Phase.STARTUP);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "cannot read pending startup steps", e);
            return 0;
        }
        List<Long> touched = new ArrayList<>();
        for (Step s : pending) {
            StepResult r = runStartupStep(s);
            record(s, r);
            if (!touched.contains(s.jobId())) {
                touched.add(s.jobId());
            }
        }
        for (long jobId : touched) {
            evaluate(jobId);
        }
        return pending.size();
    }

    StepResult runStartupStep(Step s) {
        Optional<Integration> found = registry.get(s.integration());
        if (found.isEmpty()) {
            String why = registry.unavailable().getOrDefault(s.integration(), "integration not available");
            // an integration that was configured off or whose plugin is gone has nothing to wipe in this server
            return StepResult.skipped(why);
        }
        if (!(found.get() instanceof StartupIntegration integration)) {
            return StepResult.failed("integration is not a startup integration");
        }
        Optional<Job> job;
        try {
            job = store.job(s.jobId());
        } catch (SQLException e) {
            return StepResult.failed(e);
        }
        if (job.isEmpty()) {
            return StepResult.failed("job row missing");
        }
        WipeOptions options = WipeOptions.decode(job.get().options());
        StepContext ctx = new StepContext(s.jobId(), backupDir(s.jobId(), s.uuid(), s.integration()), logger,
                System.currentTimeMillis(), options);
        try {
            if (!integration.availability().available()) {
                return StepResult.nothing(integration.availability().reason());
            }
            if (job.get().type() == JobType.RESTORE) {
                return integration.restore(ctx, backupDir(options.restoreFrom(), s.uuid(), s.integration()));
            }
            return s.uuid() == null ? integration.wipeAll(ctx) : integration.wipe(ctx, s.uuid());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "startup step " + s.integration() + " of job " + s.jobId() + " failed", e);
            return StepResult.failed(e);
        }
    }

    public void record(Step s, StepResult r) {
        try {
            store.updateStep(s.jobId(), s.uuid(), s.integration(), r.state(), r.detail(), System.currentTimeMillis());
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "cannot record step result " + s.integration() + " of job " + s.jobId()
                    + " (" + r.state() + ": " + r.detail() + ")", e);
        }
    }

    /** Recomputes a job's state from its steps; releases locks only when everything finished cleanly. */
    public JobState evaluate(long jobId) {
        try {
            Optional<Job> job = store.job(jobId);
            if (job.isEmpty() || job.get().state() == JobState.CANCELLED || job.get().state() == JobState.COMPLETED) {
                return job.map(Job::state).orElse(JobState.CANCELLED);
            }
            List<Step> steps = store.steps(jobId);
            boolean liveOpen = false;
            boolean startupOpen = false;
            int problems = 0;
            for (Step s : steps) {
                if (s.state() == StepState.PENDING) {
                    if (s.phase() == Phase.LIVE) {
                        liveOpen = true;
                    } else {
                        startupOpen = true;
                    }
                } else if (s.state().problem()) {
                    problems++;
                }
            }
            JobState previous = job.get().state();
            JobState next;
            if (liveOpen) {
                next = JobState.RUNNING;
            } else if (startupOpen) {
                next = JobState.WAITING_RESTART;
            } else if (problems > 0) {
                next = JobState.PARTIAL;
            } else {
                next = JobState.COMPLETED;
            }
            if (next != previous) {
                store.setJobState(jobId, next, problems > 0 ? problems + " step(s) need attention" : null, System.currentTimeMillis());
                if (next == JobState.COMPLETED) {
                    store.unlockJob(jobId);
                }
                notifier.accept(jobId + ":" + next.name() + ":" + problems + ":" + countPending(steps, Phase.STARTUP));
            }
            return next;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "cannot evaluate job " + jobId, e);
            return JobState.RUNNING;
        }
    }

    private static int countPending(List<Step> steps, Phase phase) {
        int n = 0;
        for (Step s : steps) {
            if (s.state() == StepState.PENDING && s.phase() == phase) {
                n++;
            }
        }
        return n;
    }

    public List<Job> openJobs() throws SQLException {
        return store.jobsIn(EnumSet.of(JobState.RUNNING, JobState.WAITING_RESTART));
    }

    /** Re-queues failed and blocked steps. */
    public int retry(long jobId) throws SQLException {
        int n = store.resetProblemSteps(jobId, System.currentTimeMillis());
        if (n > 0) {
            store.setJobState(jobId, JobState.RUNNING, "retry requested", System.currentTimeMillis());
            // re-lock targets (staff may have unlocked a partial job before retrying)
            for (var t : store.targets(jobId)) {
                store.lock(t.uuid(), jobId, "retry of job " + jobId, System.currentTimeMillis());
            }
            evaluate(jobId);
        }
        return n;
    }
}
