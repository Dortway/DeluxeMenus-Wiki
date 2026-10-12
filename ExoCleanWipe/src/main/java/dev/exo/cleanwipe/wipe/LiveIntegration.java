package dev.exo.cleanwipe.wipe;

import dev.exo.cleanwipe.storage.Records.Phase;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Runs while the server is up, through the owning plugin's own API so its caches stay consistent. Methods are
 * called on the main thread; implementations move file or network work off it themselves.
 */
public interface LiveIntegration extends Integration {

    @Override
    default Phase phase() {
        return Phase.LIVE;
    }

    /** True when the target must be offline (kicked and saved) before this step may run. */
    default boolean requiresOffline() {
        return true;
    }

    CompletableFuture<StepResult> wipe(StepContext ctx, UUID target);

    CompletableFuture<StepResult> wipeAll(StepContext ctx);

    /** Restores from the original job's backup directory, or explains the manual procedure. */
    CompletableFuture<StepResult> restore(StepContext ctx, UUID target, Path source);
}
