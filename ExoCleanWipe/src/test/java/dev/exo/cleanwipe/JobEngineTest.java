package dev.exo.cleanwipe;

import static dev.exo.cleanwipe.Rows.insert;
import static dev.exo.cleanwipe.Rows.m;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exo.cleanwipe.integrations.sql.PluginSpecs;
import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.storage.Records.JobState;
import dev.exo.cleanwipe.storage.Records.JobType;
import dev.exo.cleanwipe.storage.Records.Phase;
import dev.exo.cleanwipe.storage.Records.Step;
import dev.exo.cleanwipe.storage.Records.StepState;
import dev.exo.cleanwipe.wipe.IntegrationRegistry;
import dev.exo.cleanwipe.wipe.JobEngine;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StartupIntegration;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import dev.exo.cleanwipe.wipe.WipeOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Persistence, restart recovery, partial failures, retries and restores of wipe jobs. */
class JobEngineTest {

    static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path dir;

    /** A startup integration whose behaviour the test controls. */
    static final class Fake implements StartupIntegration {
        final String id;
        final AtomicInteger runs = new AtomicInteger();
        volatile boolean fail;

        Fake(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String covers() {
            return "test";
        }

        public Availability availability() {
            return Availability.ok();
        }

        public Preview preview(UUID target) {
            return Preview.empty();
        }

        public Preview previewAll() {
            return Preview.empty();
        }

        public StepResult wipe(StepContext ctx, UUID target) {
            runs.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("disk full");
            }
            return StepResult.done("ok");
        }

        public StepResult wipeAll(StepContext ctx) {
            return wipe(ctx, null);
        }

        public StepResult restore(StepContext ctx, Path source) {
            return StepResult.done("restored");
        }
    }

    private JobEngine engine(CoreStore store, IntegrationRegistry reg) {
        return new JobEngine(store, reg, dir.resolve("backups"), Logger.getLogger("t"));
    }

    @Test
    void jobSurvivesRestartAndStartupStepsRunOnce() throws Exception {
        Path coreDb = dir.resolve("core.db");
        IntegrationRegistry reg = new IntegrationRegistry();
        Fake a = new Fake("A");
        reg.register(a);
        long jobId;
        try (CoreStore store = new CoreStore(coreDb)) {
            JobEngine e = engine(store, reg);
            jobId = e.create(JobType.WIPE, null, "Admin", "", WipeOptions.DEFAULT,
                    List.of(new JobEngine.Target(T, "Steve", "primary")),
                    List.of(new JobEngine.StepPlan(T, "A", Phase.STARTUP)), false, null);
            assertEquals(JobState.WAITING_RESTART, e.evaluate(jobId));
            assertTrue(store.lockOf(T).isPresent(), "locked while waiting for the restart");
        }
        // "restart": a new store and engine over the same file, as onLoad does
        try (CoreStore store = new CoreStore(coreDb)) {
            JobEngine e = engine(store, reg);
            assertEquals(1, e.runStartupSteps(true));
            assertEquals(JobState.COMPLETED, store.job(jobId).orElseThrow().state());
            assertFalse(store.lockOf(T).isPresent(), "unlocked once everything finished");
            assertEquals(0, e.runStartupSteps(true), "nothing runs twice");
        }
        assertEquals(1, a.runs.get());
    }

    @Test
    void failedStepKeepsAccountLockedUntilRetrySucceeds() throws Exception {
        IntegrationRegistry reg = new IntegrationRegistry();
        Fake ok = new Fake("Ok");
        Fake bad = new Fake("Bad");
        bad.fail = true;
        reg.register(ok);
        reg.register(bad);
        try (CoreStore store = new CoreStore(dir.resolve("core.db"))) {
            JobEngine e = engine(store, reg);
            long job = e.create(JobType.PUNISH, null, "Admin", "duping", WipeOptions.DEFAULT,
                    List.of(new JobEngine.Target(T, "Steve", "primary")),
                    List.of(new JobEngine.StepPlan(T, "Ok", Phase.STARTUP), new JobEngine.StepPlan(T, "Bad", Phase.STARTUP)), false, null);
            e.runStartupSteps(true);
            assertEquals(JobState.PARTIAL, store.job(job).orElseThrow().state());
            assertTrue(store.lockOf(T).isPresent(), "partial wipe keeps the account locked");
            Step failed = store.steps(job).stream().filter(s -> s.integration().equals("Bad")).findFirst().orElseThrow();
            assertEquals(StepState.FAILED, failed.state());
            assertTrue(failed.detail().contains("disk full"));

            bad.fail = false;
            assertEquals(1, e.retry(job));
            assertEquals(JobState.WAITING_RESTART, store.job(job).orElseThrow().state());
            e.runStartupSteps(true);
            assertEquals(JobState.COMPLETED, store.job(job).orElseThrow().state());
            assertFalse(store.lockOf(T).isPresent());
            assertEquals(1, ok.runs.get(), "successful steps are not repeated by a retry");
        }
    }

    @Test
    void stepsForUninstalledPluginsAreSkippedWithReason() throws Exception {
        IntegrationRegistry reg = new IntegrationRegistry();
        reg.markUnavailable("Gone", "plugin not installed");
        try (CoreStore store = new CoreStore(dir.resolve("core.db"))) {
            JobEngine e = engine(store, reg);
            long job = e.create(JobType.WIPE, null, "Admin", "", WipeOptions.DEFAULT,
                    List.of(new JobEngine.Target(T, "Steve", "primary")),
                    List.of(new JobEngine.StepPlan(T, "Gone", Phase.STARTUP)), false, null);
            e.runStartupSteps(true);
            Step s = store.steps(job).get(0);
            assertEquals(StepState.SKIPPED, s.state());
            assertEquals("plugin not installed", s.detail());
            assertEquals(JobState.COMPLETED, store.job(job).orElseThrow().state());
        }
    }

    @Test
    void seasonResetHoldsGlobalLockAndRealSpecWorksEndToEnd() throws Exception {
        Path skills = TestDb.create(dir, "ExoSkills");
        for (int i = 0; i < 250; i++) {
            insert(skills, "progress", m("uuid", UUID.randomUUID().toString(), "skill", "mining", "level", i, "xp", 1.0));
        }
        IntegrationRegistry reg = new IntegrationRegistry();
        reg.register(PluginSpecs.create("ExoSkills", skills).orElseThrow());
        try (CoreStore store = new CoreStore(dir.resolve("core.db"))) {
            JobEngine e = engine(store, reg);
            long job = e.create(JobType.WIPE_ALL, null, "Admin", "season reset", WipeOptions.DEFAULT, List.of(),
                    List.of(new JobEngine.StepPlan(null, "ExoSkills", Phase.STARTUP)), true, null);
            e.evaluate(job);
            assertTrue(store.lockOf(null).isPresent(), "nobody can join during a season reset");
            e.runStartupSteps(true);
            assertEquals(0, TestDb.count(skills, "SELECT COUNT(*) FROM progress"));
            assertEquals(JobState.COMPLETED, store.job(job).orElseThrow().state());
            assertFalse(store.lockOf(null).isPresent());
            assertTrue(Files.exists(dir.resolve("backups/job-" + job + "/all/ExoSkills/restore.sql")));

            // restore the season reset from its backup
            long restore = e.create(JobType.RESTORE, null, "Admin", "undo", new WipeOptions(false, false, job), List.of(),
                    List.of(new JobEngine.StepPlan(null, "ExoSkills", Phase.STARTUP)), false, null);
            e.runStartupSteps(true);
            assertEquals(JobState.COMPLETED, store.job(restore).orElseThrow().state());
            assertEquals(250, TestDb.count(skills, "SELECT COUNT(*) FROM progress"));
        }
    }

    @Test
    void punishmentIsCreatedAtomicallyWithTheJob() throws Exception {
        try (CoreStore store = new CoreStore(dir.resolve("core.db"))) {
            JobEngine e = engine(store, new IntegrationRegistry());
            List<Long> ids = new ArrayList<>();
            long job = e.create(JobType.PUNISH, null, "Admin", "duping", WipeOptions.DEFAULT,
                    List.of(new JobEngine.Target(T, "Steve", "primary")), List.of(), false,
                    (c, id) -> {
                        long p = store.insertPunishment(c, dev.exo.cleanwipe.storage.Records.PunishmentKind.ACCOUNT, T, "Steve",
                                "duping", null, "Admin", 1, 2L, id);
                        ids.add(p);
                        return p;
                    });
            assertEquals(ids.get(0), store.job(job).orElseThrow().punishmentId());
        }
    }
}
