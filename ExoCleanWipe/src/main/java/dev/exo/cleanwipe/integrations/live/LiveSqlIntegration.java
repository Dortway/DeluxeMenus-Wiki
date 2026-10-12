package dev.exo.cleanwipe.integrations.live;

import dev.exo.cleanwipe.integrations.sql.SqlStartupIntegration;
import dev.exo.cleanwipe.storage.Records.StepState;
import dev.exo.cleanwipe.wipe.LiveIntegration;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Runs a database integration while its plugin is running, so no restart is needed.
 *
 * <ol>
 *   <li>The target is offline and locked (the runner kicks them first).</li>
 *   <li>On the main thread, an optional {@link Hook} updates the plugin's in-memory state through its own methods
 *       (for example ExoWoodCube's admin remove), or explains why that is not possible right now.</li>
 *   <li>After a short settle time (so the plugin's own quit save has landed) the rows are changed with the same
 *       rules and backups as the startup integration (consistent VACUUM INTO backup).</li>
 *   <li>After another settle time the rows are checked again. If the plugin wrote the data back, the change is
 *       repeated once; if it comes back again the step is moved to the next server start (never reported done).</li>
 * </ol>
 * When the plugin is not running at all, its files are not open, so the startup logic runs directly.
 */
public final class LiveSqlIntegration implements LiveIntegration {

    /** Main-thread step that updates the plugin's memory. Returns null to continue, or why it must wait for a restart. */
    public interface Hook {
        String prepare(Plugin owner, UUID target, SqlStartupIntegration base) throws Exception;
    }

    private final SqlStartupIntegration base;
    private final String pluginName;
    private final Hook hook;
    private final boolean deferIfOwns;
    private final Executor io;
    private final long settleMillis;

    /**
     * @param deferIfOwns the plugin keeps the target's objects in memory with no way to remove them live: run live
     *                    only when the target owns nothing there, otherwise move the step to the next start
     */
    public LiveSqlIntegration(SqlStartupIntegration base, String pluginName, Hook hook, boolean deferIfOwns, Executor io,
                              long settleMillis) {
        this.base = base;
        this.pluginName = pluginName;
        this.hook = hook;
        this.deferIfOwns = deferIfOwns;
        this.io = io;
        this.settleMillis = settleMillis;
        base.useLiveBackups();
    }

    @Override
    public String id() {
        return base.id();
    }

    @Override
    public String covers() {
        return base.covers() + (deferIfOwns ? " (live when the player owns none; otherwise at the next restart)" : " (live)");
    }

    @Override
    public Availability availability() {
        return base.availability();
    }

    @Override
    public Preview preview(UUID target) throws Exception {
        return base.preview(target);
    }

    @Override
    public Preview previewAll() throws Exception {
        return base.previewAll();
    }

    private Plugin running() {
        Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
        return p != null && p.isEnabled() ? p : null;
    }

    private Executor later() {
        return CompletableFuture.delayedExecutor(settleMillis, TimeUnit.MILLISECONDS, io);
    }

    @Override
    public CompletableFuture<StepResult> wipe(StepContext ctx, UUID target) {
        Plugin owner = running();
        if (owner == null) {
            return CompletableFuture.supplyAsync(() -> safe(() -> base.wipe(ctx, target)), io);
        }
        if (hook != null) {
            try {
                // the hook changes the plugin's memory, so unfinished transactions must stop us before it runs
                Preview check = base.preview(target);
                if (!check.blockers().isEmpty()) {
                    return CompletableFuture.completedFuture(StepResult.blocked(String.join("; ", check.blockers())));
                }
                String reason = hook.prepare(owner, target, base);
                if (reason != null) {
                    return CompletableFuture.completedFuture(StepResult.deferred(reason));
                }
            } catch (Exception | LinkageError e) {
                return CompletableFuture.completedFuture(StepResult.deferred("could not update " + pluginName + "'s memory ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + ")"));
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            if (deferIfOwns) {
                Preview p = safePreview(target);
                if (p == null || !p.lines().isEmpty()) {
                    return StepResult.deferred(pluginName + " keeps the player's objects in memory and has no way to remove them live");
                }
                return StepResult.nothing("nothing stored");
            }
            return safe(() -> base.wipe(ctx, target));
        }, later()).thenCompose(first -> verify(ctx, target, first, true));
    }

    /** Re-checks after a settle time; repeats once if the plugin wrote the data back, then gives up to a restart. */
    private CompletableFuture<StepResult> verify(StepContext ctx, UUID target, StepResult result, boolean retry) {
        if (result.state() != StepState.DONE && result.state() != StepState.NOTHING) {
            return CompletableFuture.completedFuture(result);
        }
        return CompletableFuture.supplyAsync(() -> {
            Preview p = safePreview(target);
            if (p != null && p.lines().isEmpty()) {
                return result;
            }
            if (!retry) {
                return StepResult.deferred(pluginName + " wrote the player's data back after the wipe");
            }
            return null;
        }, later()).thenCompose(r -> r != null ? CompletableFuture.completedFuture(r)
                : CompletableFuture.supplyAsync(() -> safe(() -> base.wipe(ctx, target)), io)
                        .thenCompose(again -> verify(ctx, target, again, false)));
    }

    @Override
    public CompletableFuture<StepResult> wipeAll(StepContext ctx) {
        if (running() == null) {
            return CompletableFuture.supplyAsync(() -> safe(() -> base.wipeAll(ctx)), io);
        }
        if (hook != null || deferIfOwns) {
            return CompletableFuture.completedFuture(StepResult.deferred("the season reset of " + pluginName + " needs " + pluginName + " stopped"));
        }
        return CompletableFuture.supplyAsync(() -> safe(() -> base.wipeAll(ctx)), later()).thenCompose(r -> {
            if (r.state() != StepState.DONE && r.state() != StepState.NOTHING) {
                return CompletableFuture.completedFuture(r);
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return base.previewAll().lines().isEmpty() ? r
                            : StepResult.deferred(pluginName + " wrote data back after the season reset");
                } catch (Exception e) {
                    return StepResult.deferred("could not verify " + pluginName + ": " + e.getMessage());
                }
            }, later());
        });
    }

    @Override
    public CompletableFuture<StepResult> restore(StepContext ctx, UUID target, Path source) {
        if (running() == null) {
            return CompletableFuture.supplyAsync(() -> safe(() -> base.restore(ctx, source)), io);
        }
        return CompletableFuture.completedFuture(StepResult.deferred("restores of " + pluginName + " run while it is stopped"));
    }

    private Preview safePreview(UUID target) {
        try {
            return base.preview(target);
        } catch (Exception e) {
            return null;
        }
    }

    private interface Work {
        StepResult run() throws Exception;
    }

    private static StepResult safe(Work w) {
        try {
            return w.run();
        } catch (Exception e) {
            return StepResult.failed(e);
        }
    }
}
