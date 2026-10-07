package dev.exoquests.core.time;

import java.time.Instant;

/** Injectable time source so reset logic can be tested deterministically. */
@FunctionalInterface
public interface Clock {
    Clock SYSTEM = Instant::now;

    Instant now();
}
