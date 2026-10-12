package dev.exo.cleanwipe.wipe;

import dev.exo.cleanwipe.storage.Records.Phase;
import java.util.UUID;

/**
 * Runs in ExoCleanWipe's {@code onLoad}, before the owning plugin has opened or cached its data, so nothing can be
 * written back over the wipe. Methods run synchronously during server start.
 */
public interface StartupIntegration extends Integration {

    @Override
    default Phase phase() {
        return Phase.STARTUP;
    }

    StepResult wipe(StepContext ctx, UUID target) throws Exception;

    /** Season reset of every player stored by this plugin. */
    StepResult wipeAll(StepContext ctx) throws Exception;

    /** Re-applies what {@link #wipe} or {@link #wipeAll} saved in {@code source} (the original job's backup dir). */
    StepResult restore(StepContext ctx, java.nio.file.Path source) throws Exception;
}
