package dev.exo.cleanwipe.wipe;

import dev.exo.cleanwipe.storage.Records.Phase;
import java.util.UUID;

/**
 * One kind of stored data that a wipe can remove. Integrations never guess: when a plugin, file or table is not
 * found they report {@link Availability#missing} and the wipe records the step as skipped with that reason.
 */
public interface Integration {

    /** Stable id used in job steps and config, e.g. {@code "ExoVaults"}. */
    String id();

    /** Lowercase, staff-facing summary of what this integration resets. */
    String covers();

    Phase phase();

    Availability availability();

    /** Read-only. May be called off the main thread. */
    Preview preview(UUID target) throws Exception;

    /** Read-only preview for a season reset. */
    Preview previewAll() throws Exception;

    record Availability(boolean available, String reason) {
        public static Availability ok() {
            return new Availability(true, "");
        }

        public static Availability missing(String reason) {
            return new Availability(false, reason);
        }
    }
}
