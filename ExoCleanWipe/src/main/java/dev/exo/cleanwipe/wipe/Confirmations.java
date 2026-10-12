package dev.exo.cleanwipe.wipe;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived confirmation codes. A code is bound to the staff member who requested the preview (or the console) and
 * to exactly that preview; it can be used once and expires after the configured number of seconds.
 */
public final class Confirmations<T> {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    public record Entry<T>(String token, UUID owner, long expiresAt, T payload) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry<T>> entries = new ConcurrentHashMap<>();

    /** @param owner null for the console */
    public Entry<T> issue(UUID owner, long ttlMillis, T payload, long now) {
        String token;
        do {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
            token = sb.toString();
        } while (entries.containsKey(token));
        Entry<T> e = new Entry<>(token, owner, now + ttlMillis, payload);
        entries.put(token, e);
        return e;
    }

    public enum Check { OK, UNKNOWN, WRONG_OWNER }

    public Check check(String token, UUID owner, long now) {
        Entry<T> e = entries.get(token);
        if (e == null || e.expiresAt() < now) {
            return Check.UNKNOWN;
        }
        return java.util.Objects.equals(e.owner(), owner) ? Check.OK : Check.WRONG_OWNER;
    }

    /** Removes and returns the entry if it is valid for this owner. */
    public Optional<Entry<T>> consume(String token, UUID owner, long now) {
        if (check(token, owner, now) != Check.OK) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.remove(token));
    }

    public Optional<Entry<T>> peek(String token) {
        return Optional.ofNullable(entries.get(token));
    }

    public Optional<Entry<T>> remove(String token) {
        return Optional.ofNullable(entries.remove(token));
    }

    /** Removes expired entries and returns them so the caller can unfreeze their targets. */
    public java.util.List<Entry<T>> expire(long now) {
        java.util.List<Entry<T>> out = new java.util.ArrayList<>();
        entries.values().removeIf(e -> {
            if (e.expiresAt() < now) {
                out.add(e);
                return true;
            }
            return false;
        });
        return out;
    }

    public boolean isPending(java.util.function.Predicate<T> test) {
        return entries.values().stream().anyMatch(e -> test.test(e.payload()));
    }
}
