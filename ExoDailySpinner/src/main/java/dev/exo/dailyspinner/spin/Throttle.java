package dev.exo.dailyspinner.spin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** Simple per-key rate limiter used against click and command spam. */
public final class Throttle {

    private final ConcurrentMap<UUID, Long> last = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private volatile long intervalMillis;

    public Throttle(long intervalMillis) {
        this(intervalMillis, System::currentTimeMillis);
    }

    public Throttle(long intervalMillis, LongSupplier clock) {
        this.intervalMillis = intervalMillis;
        this.clock = clock;
    }

    public void setInterval(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    /** @return true if the action is allowed now (and records it) */
    public boolean tryPass(UUID key) {
        long now = clock.getAsLong();
        boolean[] allowed = {false};
        last.compute(key, (k, previous) -> {
            if (previous == null || now - previous >= intervalMillis || now < previous) {
                allowed[0] = true;
                return now;
            }
            return previous;
        });
        return allowed[0];
    }

    public void forget(UUID key) {
        last.remove(key);
    }
}
