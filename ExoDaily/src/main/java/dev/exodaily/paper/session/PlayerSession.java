package dev.exodaily.paper.session;

import dev.exodaily.core.claim.ClickGuard;
import dev.exodaily.core.service.DailyView;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Transient state of one online player. Removed when the player quits. */
public final class PlayerSession {

    private final UUID uuid;
    private final ClickGuard guard = new ClickGuard();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile DailyView view;
    private volatile long lastOpenNanos;
    private volatile boolean hasOpened;
    private volatile String lastCountdown;

    PlayerSession(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    public ClickGuard guard() {
        return guard;
    }

    /** Incremented whenever menus rendered earlier must be treated as stale. */
    public long epoch() {
        return epoch.get();
    }

    public long invalidate() {
        return epoch.incrementAndGet();
    }

    public boolean beginLoading() {
        return loading.compareAndSet(false, true);
    }

    public void endLoading() {
        loading.set(false);
    }

    public DailyView view() {
        return view;
    }

    public void view(DailyView view) {
        this.view = view;
    }

    /** Throttles /daily; returns false if called again within the cooldown. */
    public synchronized boolean tryOpen(long nowNanos, long cooldownNanos) {
        if (hasOpened && nowNanos - lastOpenNanos < cooldownNanos) {
            return false;
        }
        hasOpened = true;
        lastOpenNanos = nowNanos;
        return true;
    }

    public String lastCountdown() {
        return lastCountdown;
    }

    public void lastCountdown(String lastCountdown) {
        this.lastCountdown = lastCountdown;
    }
}
