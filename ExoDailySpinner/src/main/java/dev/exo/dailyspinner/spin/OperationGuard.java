package dev.exo.dailyspinner.spin;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Allows at most one spin or claim operation per player at a time. Every acquisition receives a
 * unique token; releasing with a stale token (for example from a replayed callback) has no effect.
 */
public final class OperationGuard {

    public enum Kind { SPIN, CLAIM, RESUME, ADMIN }

    public record Token(UUID player, UUID operationId, Kind kind, long acquiredAt) {
    }

    private final ConcurrentMap<UUID, Token> active = new ConcurrentHashMap<>();

    public Optional<Token> tryAcquire(UUID player, Kind kind) {
        Token token = new Token(player, UUID.randomUUID(), kind, System.currentTimeMillis());
        Token previous = active.putIfAbsent(player, token);
        return previous == null ? Optional.of(token) : Optional.empty();
    }

    /** @return true when the token was the active one and has now been released */
    public boolean release(Token token) {
        return token != null && active.remove(token.player(), token);
    }

    public boolean isHeld(Token token) {
        return token != null && token.equals(active.get(token.player()));
    }

    public Optional<Token> current(UUID player) {
        return Optional.ofNullable(active.get(player));
    }

    public boolean isBusy(UUID player) {
        return active.containsKey(player);
    }

    public void clear() {
        active.clear();
    }
}
