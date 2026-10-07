package dev.exoquests.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Purchase records and their state transitions. */
public final class PurchaseStore {

    private PurchaseStore() {
    }

    public static void insert(Connection c, PurchaseRecord r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO purchases(purchase_id, uuid, item_id, price, revision, reward_type, reward_snapshot, "
                        + "state, note, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, r.purchaseId());
            ps.setString(2, r.player().toString());
            ps.setString(3, r.itemId());
            ps.setInt(4, r.price());
            ps.setString(5, r.revision());
            ps.setString(6, r.rewardType());
            ps.setString(7, r.rewardSnapshot());
            ps.setString(8, r.state().name());
            ps.setString(9, r.note());
            ps.setLong(10, r.createdAt());
            ps.setLong(11, r.updatedAt());
            ps.executeUpdate();
        }
    }

    /** Conditional transition; returns false if the record was not in {@code from}. */
    public static boolean transition(Connection c, String purchaseId, PurchaseState from, PurchaseState to,
                                     String note, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE purchases SET state = ?, note = COALESCE(?, note), updated_at = ? "
                        + "WHERE purchase_id = ? AND state = ?")) {
            ps.setString(1, to.name());
            ps.setString(2, note);
            ps.setLong(3, now);
            ps.setString(4, purchaseId);
            ps.setString(5, from.name());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Moves a purchase from {@code from} to REFUNDED and returns the price in one transaction. The ledger
     * reference {@code refund:<id>} makes a second refund of the same purchase impossible.
     */
    public static Optional<PointsStore.Change> refund(Connection c, String purchaseId, PurchaseState from,
                                                      long maxBalance, String actor, String note, long now)
            throws SQLException {
        Optional<PurchaseRecord> rec = find(c, purchaseId);
        if (rec.isEmpty() || rec.get().state() != from) {
            return Optional.empty();
        }
        if (!transition(c, purchaseId, from, PurchaseState.REFUNDED, note, now)) {
            return Optional.empty();
        }
        PointsStore.Change change = PointsStore.credit(c, rec.get().player(), rec.get().price(), maxBalance,
                "refund:" + rec.get().itemId(), "refund:" + purchaseId, actor, now);
        if (!change.applied() && change.failure() == PointsStore.Failure.DUPLICATE_REFERENCE) {
            throw new SQLException("purchase " + purchaseId + " was already refunded");
        }
        return Optional.of(change);
    }

    public static Optional<PurchaseRecord> find(Connection c, String purchaseId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM purchases WHERE purchase_id = ?")) {
            ps.setString(1, purchaseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    public static List<PurchaseRecord> byState(Connection c, PurchaseState state, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM purchases WHERE state = ? ORDER BY created_at LIMIT ?")) {
            ps.setString(1, state.name());
            ps.setInt(2, limit);
            return readAll(ps);
        }
    }

    public static List<PurchaseRecord> byPlayerAndState(Connection c, UUID player, PurchaseState state)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM purchases WHERE uuid = ? AND state = ? ORDER BY created_at")) {
            ps.setString(1, player.toString());
            ps.setString(2, state.name());
            return readAll(ps);
        }
    }

    /** Startup recovery: deliveries that were in progress when the server stopped need a human decision. */
    public static int flagInterrupted(Connection c, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE purchases SET state = 'NEEDS_REVIEW', note = 'server stopped during delivery', updated_at = ? "
                        + "WHERE state = 'DELIVERING'")) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        }
    }

    public static int countByState(Connection c, PurchaseState state) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM purchases WHERE state = ?")) {
            ps.setString(1, state.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static List<PurchaseRecord> readAll(PreparedStatement ps) throws SQLException {
        List<PurchaseRecord> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(read(rs));
            }
        }
        return out;
    }

    private static PurchaseRecord read(ResultSet rs) throws SQLException {
        return new PurchaseRecord(rs.getString("purchase_id"), UUID.fromString(rs.getString("uuid")),
                rs.getString("item_id"), rs.getInt("price"), rs.getString("revision"), rs.getString("reward_type"),
                rs.getString("reward_snapshot"), PurchaseState.valueOf(rs.getString("state")), rs.getString("note"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
