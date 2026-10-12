package dev.exo.cleanwipe.wipe;

import dev.exo.cleanwipe.storage.Records.Job;
import dev.exo.cleanwipe.storage.Records.JobType;
import dev.exo.cleanwipe.storage.Records.Phase;
import dev.exo.cleanwipe.storage.Records.Step;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Drives live steps in bounded batches on a repeating task: at most {@code batchSize} steps are started per run and
 * at most {@code batchSize} run at the same time, so a season reset of thousands of players never blocks the main
 * thread. Steps whose target is still online are not started; the target is kicked again instead.
 */
public final class LiveRunner {

    private static final long STEP_TIMEOUT_MINUTES = 10;

    private final Plugin plugin;
    private final JobEngine engine;
    private final int batchSize;
    private final int intervalTicks;
    private final Logger logger;
    private final Function<Long, Component> lockScreen;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Long, Job> jobCache = new ConcurrentHashMap<>();
    private BukkitTask task;

    public LiveRunner(Plugin plugin, JobEngine engine, int batchSize, int intervalTicks, Function<Long, Component> lockScreen) {
        this.plugin = plugin;
        this.engine = engine;
        this.batchSize = batchSize;
        this.intervalTicks = intervalTicks;
        this.logger = plugin.getLogger();
        this.lockScreen = lockScreen;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    private static String key(Step s) {
        return s.jobId() + "/" + s.uuid() + "/" + s.integration();
    }

    private void tick() {
        if (inFlight.size() >= batchSize) {
            return;
        }
        List<Step> pending;
        try {
            pending = engine.store().pendingSteps(Phase.LIVE, batchSize * 4 + inFlight.size());
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "cannot read pending live steps", e);
            return;
        }
        int started = 0;
        for (Step s : pending) {
            if (started >= batchSize || inFlight.size() >= batchSize) {
                break;
            }
            if (inFlight.contains(key(s))) {
                continue;
            }
            Optional<Integration> found = engine.registry().get(s.integration());
            if (found.isEmpty() || !(found.get() instanceof LiveIntegration live)) {
                String why = engine.registry().unavailable().getOrDefault(s.integration(), "plugin not installed");
                finish(s, StepResult.skipped(why));
                continue;
            }
            if (s.uuid() != null && live.requiresOffline()) {
                Player online = Bukkit.getPlayer(s.uuid());
                if (online != null) {
                    online.kick(lockScreen.apply(s.jobId()));
                    continue; // next tick, after the server has saved the player
                }
            }
            Job job = job(s.jobId());
            if (job == null) {
                finish(s, StepResult.failed("job row missing"));
                continue;
            }
            WipeOptions options = WipeOptions.decode(job.options());
            StepContext ctx = new StepContext(s.jobId(), engine.backupDir(s.jobId(), s.uuid(), s.integration()), logger,
                    System.currentTimeMillis(), options);
            if (!live.availability().available()) {
                finish(s, StepResult.failed(live.availability().reason()));
                continue;
            }
            inFlight.add(key(s));
            started++;
            CompletableFuture<StepResult> f;
            try {
                if (job.type() == JobType.RESTORE) {
                    f = live.restore(ctx, s.uuid(), engine.backupDir(options.restoreFrom(), s.uuid(), s.integration()));
                } else {
                    f = s.uuid() == null ? live.wipeAll(ctx) : live.wipe(ctx, s.uuid());
                }
            } catch (Throwable t) {
                f = CompletableFuture.completedFuture(StepResult.failed(t));
            }
            f.orTimeout(STEP_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                    .exceptionally(StepResult::failed)
                    .thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> {
                        inFlight.remove(key(s));
                        finish(s, r);
                    }));
        }
    }

    private Job job(long id) {
        return jobCache.computeIfAbsent(id, k -> {
            try {
                return engine.store().job(k).orElse(null);
            } catch (SQLException e) {
                return null;
            }
        });
    }

    private void finish(Step s, StepResult r) {
        if (r.state() == dev.exo.cleanwipe.storage.Records.StepState.DEFERRED) {
            try {
                engine.store().deferToStartup(s.jobId(), s.uuid(), s.integration(), "next restart: " + r.detail(), System.currentTimeMillis());
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "could not defer step " + s.integration() + " of job " + s.jobId(), e);
            }
            logger.info("job " + s.jobId() + " " + s.integration() + " moved to the next restart: " + r.detail());
            engine.evaluate(s.jobId());
            jobCache.remove(s.jobId());
            return;
        }
        engine.record(s, r);
        if (r.state().problem()) {
            logger.warning("job " + s.jobId() + " step " + s.integration() + (s.uuid() == null ? "" : " for " + s.uuid())
                    + ": " + r.state() + " - " + r.detail());
        }
        engine.evaluate(s.jobId());
        jobCache.remove(s.jobId());
    }

    public int inFlight() {
        return inFlight.size();
    }
}
