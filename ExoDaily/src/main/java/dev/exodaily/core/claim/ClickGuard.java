package dev.exodaily.core.claim;

/**
 * Per-player click throttle and single-flight guard. A new claim cannot start while a previous
 * one is still in flight, and clicks closer together than the cooldown are ignored.
 */
public final class ClickGuard {

    private boolean busy;
    private long lastClickNanos;
    private boolean clicked;

    /** Starts an exclusive operation (a claim) if not throttled and nothing else is in flight. */
    public synchronized boolean tryBegin(long nowNanos, long cooldownNanos) {
        if (busy || throttled(nowNanos, cooldownNanos)) {
            return false;
        }
        busy = true;
        mark(nowNanos);
        return true;
    }

    /** Accepts a non-exclusive click (navigation) if not throttled. */
    public synchronized boolean tryClick(long nowNanos, long cooldownNanos) {
        if (throttled(nowNanos, cooldownNanos)) {
            return false;
        }
        mark(nowNanos);
        return true;
    }

    public synchronized void end() {
        busy = false;
    }

    public synchronized boolean busy() {
        return busy;
    }

    private boolean throttled(long nowNanos, long cooldownNanos) {
        return clicked && nowNanos - lastClickNanos < cooldownNanos;
    }

    private void mark(long nowNanos) {
        clicked = true;
        lastClickNanos = nowNanos;
    }
}
