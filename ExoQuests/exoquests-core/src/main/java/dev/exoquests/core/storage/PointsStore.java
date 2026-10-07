package dev.exoquests.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Balance and ledger operations. Methods take the caller's connection so they compose into a larger
 * transaction (for example a quest completion or a purchase). Every balance change writes exactly one
 * ledger row whose {@code ref_id} is unique, so replaying the same logical operation is rejected.
 */
public final class PointsStore {

    private PointsStore() {
    }

    /** Result of a balance mutation. {@code applied=false} means nothing changed. */
    public record Change(boolean applied, long delta, long balance, Failure failure) {
        static Change ok(long delta, long balance) {
            return new Change(true, delta, balance, null);
        }

        static Change failed(Failure f, long balance) {
            return new Change(false, 0, balance, f);
        }
    }

    public enum Failure { INSUFFICIENT_FUNDS, DUPLICATE_REFERENCE, AT_MAXIMUM }

    static void ensurePlayer(Connection c, UUID uuid, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO players(uuid, points, updated_at) VALUES (?, 0, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, now);
            ps.executeUpdate();
        }
    }

    public static long balance(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT points FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    static boolean referenceExists(Connection c, String refId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM point_ledger WHERE ref_id = ?")) {
            ps.setString(1, refId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Adds up to {@code amount} points, saturating at {@code maxBalance}. A credit that would exceed the
     * maximum is reduced; the actual delta is recorded in the ledger and returned.
     */
    public static Change credit(Connection c, UUID uuid, long amount, long maxBalance, String reason,
                                String refId, String actor, long now) throws SQLException {
        if (amount < 0) {
            throw new IllegalArgumentException("credit amount must not be negative");
        }
        ensurePlayer(c, uuid, now);
        if (referenceExists(c, refId)) {
            return Change.failed(Failure.DUPLICATE_REFERENCE, balance(c, uuid));
        }
        long current = balance(c, uuid);
        long room = Math.max(0, maxBalance - current);
        long delta = Math.min(amount, room);
        if (delta == 0 && amount > 0) {
            return Change.failed(Failure.AT_MAXIMUM, current);
        }
        long after = current + delta;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE players SET points = ?, updated_at = ? WHERE uuid = ? AND points = ?")) {
            ps.setLong(1, after);
            ps.setLong(2, now);
            ps.setString(3, uuid.toString());
            ps.setLong(4, current);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("concurrent balance modification detected");
            }
        }
        ledger(c, uuid, delta, after, reason, refId, actor, now);
        return Change.ok(delta, after);
    }

    /** Removes exactly {@code amount} points only if the balance covers it. */
    public static Change debit(Connection c, UUID uuid, long amount, String reason, String refId,
                               String actor, long now) throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("debit amount must be positive");
        }
        ensurePlayer(c, uuid, now);
        if (referenceExists(c, refId)) {
            return Change.failed(Failure.DUPLICATE_REFERENCE, balance(c, uuid));
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE players SET points = points - ?, updated_at = ? WHERE uuid = ? AND points >= ?")) {
            ps.setLong(1, amount);
            ps.setLong(2, now);
            ps.setString(3, uuid.toString());
            ps.setLong(4, amount);
            if (ps.executeUpdate() != 1) {
                return Change.failed(Failure.INSUFFICIENT_FUNDS, balance(c, uuid));
            }
        }
        long after = balance(c, uuid);
        ledger(c, uuid, -amount, after, reason, refId, actor, now);
        return Change.ok(-amount, after);
    }

    /** Sets an absolute balance in {@code [0, maxBalance]}. */
    public static Change set(Connection c, UUID uuid, long value, long maxBalance, String reason, String refId,
                             String actor, long now) throws SQLException {
        if (value < 0 || value > maxBalance) {
            throw new IllegalArgumentException("balance out of range");
        }
        ensurePlayer(c, uuid, now);
        long current = balance(c, uuid);
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE players SET points = ?, updated_at = ? WHERE uuid = ?")) {
            ps.setLong(1, value);
            ps.setLong(2, now);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
        ledger(c, uuid, value - current, value, reason, refId, actor, now);
        return Change.ok(value - current, value);
    }

    private static void ledger(Connection c, UUID uuid, long delta, long after, String reason, String refId,
                               String actor, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO point_ledger(uuid, delta, balance_after, reason, ref_id, actor, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, delta);
            ps.setLong(3, after);
            ps.setString(4, reason);
            ps.setString(5, refId);
            ps.setString(6, actor);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }
}
