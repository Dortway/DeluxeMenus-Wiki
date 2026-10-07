package dev.exo.dailyspinner.storage;

import dev.exo.dailyspinner.cooldown.CooldownCalculator;
import dev.exo.dailyspinner.reward.RewardSnapshot;
import dev.exo.dailyspinner.reward.RewardType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All persistent state transitions. Every method is synchronous JDBC and must be called off the
 * server thread (the plugin routes calls through {@link Database}'s dedicated executor).
 *
 * <p>Multi-step changes run in {@code BEGIN IMMEDIATE} transactions with conditional updates
 * ({@code WHERE status = ...}) so a replayed or concurrent request can never consume an
 * entitlement or deliver a reward twice.</p>
 */
public final class SpinRepository {

    private static final String SPIN_COLUMNS = "id, player_uuid, created_at, source, status, reward_id, reward_type, "
            + "item_data, item_amount, commands, display_data, display_name, rarity_id, announcement";

    private final Connection connection;

    public SpinRepository(Connection connection) {
        this.connection = connection;
    }

    // ------------------------------------------------------------------ reservation

    /**
     * Atomically checks entitlement, consumes it and stores the chosen reward snapshot.
     *
     * <p>If the player already has an undelivered spin it is returned unchanged instead of
     * rolling again, which makes closing menus, reconnecting and retrying safe.</p>
     */
    public synchronized ReservationResult reserve(UUID player, String operationId, RewardSnapshot snapshot,
                                                  long now, long cooldownMillis, ConsumeOrder order) throws SQLException {
        Sql.begin(connection);
        try {
            Optional<SpinRecord> active = findActiveSpin(player);
            if (active.isPresent()) {
                Sql.commit(connection);
                return ReservationResult.existing(active.get());
            }
            if (spinExists(operationId)) {
                Sql.rollbackQuietly(connection);
                return ReservationResult.duplicate();
            }
            ensurePlayer(player, now);
            Long lastDaily;
            int bonus;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT last_daily_spin, bonus_spins FROM players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    long value = rs.getLong(1);
                    lastDaily = rs.wasNull() ? null : value;
                    bonus = rs.getInt(2);
                }
            }
            boolean dailyReady = CooldownCalculator.isReady(lastDaily, cooldownMillis, now);
            SpinSource source = order.choose(dailyReady, bonus > 0);
            if (source == null) {
                Sql.rollbackQuietly(connection);
                return ReservationResult.unavailable(CooldownCalculator.remaining(lastDaily, cooldownMillis, now));
            }
            int updated;
            if (source == SpinSource.DAILY) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE players SET last_daily_spin = ?, updated_at = ? WHERE uuid = ? AND "
                                + (lastDaily == null ? "last_daily_spin IS NULL" : "last_daily_spin = ?"))) {
                    ps.setLong(1, now);
                    ps.setLong(2, now);
                    ps.setString(3, player.toString());
                    if (lastDaily != null) {
                        ps.setLong(4, lastDaily);
                    }
                    updated = ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE players SET bonus_spins = bonus_spins - 1, updated_at = ? WHERE uuid = ? AND bonus_spins > 0")) {
                    ps.setLong(1, now);
                    ps.setString(2, player.toString());
                    updated = ps.executeUpdate();
                }
            }
            if (updated != 1) {
                Sql.rollbackQuietly(connection);
                return ReservationResult.unavailable(CooldownCalculator.remaining(lastDaily, cooldownMillis, now));
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO spins (" + SPIN_COLUMNS + ", updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, operationId);
                ps.setString(2, player.toString());
                ps.setLong(3, now);
                ps.setString(4, source.name());
                ps.setString(5, SpinStatus.RESERVED.name());
                ps.setString(6, snapshot.rewardId());
                ps.setString(7, snapshot.type().name());
                setBytes(ps, 8, snapshot.itemData());
                ps.setInt(9, snapshot.type() == RewardType.ITEM ? snapshot.amount() : 0);
                ps.setString(10, snapshot.type() == RewardType.COMMAND ? snapshot.commandsAsText() : null);
                setBytes(ps, 11, snapshot.displayData());
                ps.setString(12, snapshot.displayName());
                ps.setString(13, snapshot.rarityId());
                ps.setString(14, snapshot.announcement());
                ps.setLong(15, now);
                ps.executeUpdate();
            }
            audit(now, player, operationId, "RESERVED", source.name() + " reward=" + snapshot.rewardId());
            Sql.commit(connection);
            return ReservationResult.reserved(new SpinRecord(operationId, player, now, source, SpinStatus.RESERVED, snapshot));
        } catch (SQLException | RuntimeException e) {
            Sql.rollbackQuietly(connection);
            throw e;
        }
    }

    // ------------------------------------------------------------------ delivery

    /**
     * Moves a spin from RESERVED to DELIVERING. Only one caller can ever win this transition.
     *
     * @return the stored spin when this caller may deliver it; empty if it was already handled
     */
    public synchronized Optional<SpinRecord> beginDelivery(String spinId, UUID player, long now) throws SQLException {
        Sql.begin(connection);
        try {
            int updated;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE spins SET status = ?, updated_at = ? WHERE id = ? AND player_uuid = ? AND status = ?")) {
                ps.setString(1, SpinStatus.DELIVERING.name());
                ps.setLong(2, now);
                ps.setString(3, spinId);
                ps.setString(4, player.toString());
                ps.setString(5, SpinStatus.RESERVED.name());
                updated = ps.executeUpdate();
            }
            if (updated != 1) {
                Sql.rollbackQuietly(connection);
                return Optional.empty();
            }
            Optional<SpinRecord> spin = findSpin(spinId);
            Sql.commit(connection);
            return spin;
        } catch (SQLException | RuntimeException e) {
            Sql.rollbackQuietly(connection);
            throw e;
        }
    }

    /** Returns a DELIVERING spin to RESERVED. Only valid when nothing at all was handed out. */
    public synchronized boolean revertDelivery(String spinId, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE spins SET status = ?, updated_at = ? WHERE id = ? AND status = ?")) {
            ps.setString(1, SpinStatus.RESERVED.name());
            ps.setLong(2, now);
            ps.setString(3, spinId);
            ps.setString(4, SpinStatus.DELIVERING.name());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Finishes a delivery: stores overflow items as pending rewards and marks the spin DELIVERED,
     * all in one transaction.
     *
     * @param overflowAmount items that did not fit (0 if everything was given)
     */
    public synchronized boolean completeDelivery(String spinId, UUID player, byte[] overflowItem, int overflowAmount,
                                                 long now, String detail) throws SQLException {
        Sql.begin(connection);
        try {
            int updated;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE spins SET status = ?, updated_at = ?, detail = ? WHERE id = ? AND status = ?")) {
                ps.setString(1, SpinStatus.DELIVERED.name());
                ps.setLong(2, now);
                ps.setString(3, detail);
                ps.setString(4, spinId);
                ps.setString(5, SpinStatus.DELIVERING.name());
                updated = ps.executeUpdate();
            }
            if (updated != 1) {
                Sql.rollbackQuietly(connection);
                return false;
            }
            if (overflowAmount > 0 && overflowItem != null) {
                insertPending(player, spinId, overflowItem, overflowAmount, now);
            }
            audit(now, player, spinId, "DELIVERED", detail);
            Sql.commit(connection);
            return true;
        } catch (SQLException | RuntimeException e) {
            Sql.rollbackQuietly(connection);
            throw e;
        }
    }

    /** Marks a spin FAILED (nothing delivered) so administrators can reconcile it. */
    public synchronized boolean failDelivery(String spinId, long now, String detail) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE spins SET status = ?, updated_at = ?, detail = ? WHERE id = ? AND status IN (?, ?)")) {
            ps.setString(1, SpinStatus.FAILED.name());
            ps.setLong(2, now);
            ps.setString(3, detail);
            ps.setString(4, spinId);
            ps.setString(5, SpinStatus.DELIVERING.name());
            ps.setString(6, SpinStatus.RESERVED.name());
            return ps.executeUpdate() == 1;
        }
    }

    /** Stores items for a player whose reservation could not reach the inventory at all. */
    public synchronized boolean moveReservedToPending(String spinId, UUID player, long now) throws SQLException {
        Sql.begin(connection);
        try {
            Optional<SpinRecord> spin = findSpin(spinId);
            if (spin.isEmpty() || spin.get().status() != SpinStatus.DELIVERING
                    || spin.get().snapshot().type() != RewardType.ITEM) {
                Sql.rollbackQuietly(connection);
                return false;
            }
            RewardSnapshot snapshot = spin.get().snapshot();
            insertPending(player, spinId, snapshot.itemData(), snapshot.amount(), now);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE spins SET status = ?, updated_at = ?, detail = ? WHERE id = ? AND status = ?")) {
                ps.setString(1, SpinStatus.DELIVERED.name());
                ps.setLong(2, now);
                ps.setString(3, "stored as pending reward");
                ps.setString(4, spinId);
                ps.setString(5, SpinStatus.DELIVERING.name());
                ps.executeUpdate();
            }
            audit(now, player, spinId, "DELIVERED", "stored as pending reward");
            Sql.commit(connection);
            return true;
        } catch (SQLException | RuntimeException e) {
            Sql.rollbackQuietly(connection);
            throw e;
        }
    }

    // ------------------------------------------------------------------ recovery

    /**
     * Run once at startup: anything left mid-delivery by a crash or forced stop is flagged
     * UNCERTAIN for administrator reconciliation and is never replayed automatically.
     */
    public synchronized List<ReconcileEntry> flagInterruptedDeliveries(long now) throws SQLException {
        Sql.begin(connection);
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE spins SET status = ?, updated_at = ?, detail = ? WHERE status = ?")) {
                ps.setString(1, SpinStatus.UNCERTAIN.name());
                ps.setLong(2, now);
                ps.setString(3, "server stopped during delivery");
                ps.setString(4, SpinStatus.DELIVERING.name());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE pending_rewards SET status = ?, updated_at = ? WHERE status = ?")) {
                ps.setString(1, PendingStatus.UNCERTAIN.name());
                ps.setLong(2, now);
                ps.setString(3, PendingStatus.CLAIMING.name());
                ps.executeUpdate();
            }
            Sql.commit(connection);
        } catch (SQLException | RuntimeException e) {
            Sql.rollbackQuietly(connection);
            throw e;
        }
        return listReconcile(now - 1000);
    }

    /** Lists entries needing reconciliation; {@code sinceUpdated} limits results (use 0 for all). */
    public synchronized List<ReconcileEntry> listReconcile(long sinceUpdated) throws SQLException {
        List<ReconcileEntry> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, player_uuid, reward_id, reward_type, status, updated_at, detail FROM spins "
                        + "WHERE status IN (?, ?) AND updated_at >= ? ORDER BY updated_at")) {
            ps.setString(1, SpinStatus.UNCERTAIN.name());
            ps.setString(2, SpinStatus.FAILED.name());
            ps.setLong(3, sinceUpdated);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ReconcileEntry("S:" + rs.getString(1), UUID.fromString(rs.getString(2)),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7)));
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, player_uuid, spin_id, amount, status, updated_at FROM pending_rewards "
                        + "WHERE status = ? AND updated_at >= ? ORDER BY updated_at")) {
            ps.setString(1, PendingStatus.UNCERTAIN.name());
            ps.setLong(2, sinceUpdated);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ReconcileEntry("P:" + rs.getLong(1), UUID.fromString(rs.getString(2)),
                            rs.getString(3), "PENDING_ITEM", rs.getString(5), rs.getLong(6),
                            "claim interrupted, amount=" + rs.getInt(4)));
                }
            }
        }
        return result;
    }

    /** Result of resolving a reconciliation entry. */
    public record Resolution(boolean found, UUID player, RewardSnapshot snapshot) {
    }

    /**
     * Resolves a reconciliation entry. For "regrant": spin item rewards are re-queued as pending
     * items and the snapshot is returned (so command rewards can be explicitly re-run by the caller);
     * interrupted pending claims are set back to PENDING. For "dismiss" the entry is closed.
     */
    public synchronized Resolution resolve(String key, boolean regrant, long now, String actor) throws SQLException {
        Sql.begin(connection);
        try {
            if (key.startsWith("S:")) {
                String spinId = key.substring(2);
                Optional<SpinRecord> spin = findSpin(spinId);
                if (spin.isEmpty() || (spin.get().status() != SpinStatus.UNCERTAIN && spin.get().status() != SpinStatus.FAILED)) {
                    Sql.rollbackQuietly(connection);
                    return new Resolution(false, null, null);
                }
                SpinRecord record = spin.get();
                if (regrant && record.snapshot().type() == RewardType.ITEM) {
                    insertPending(record.player(), spinId, record.snapshot().itemData(), record.snapshot().amount(), now);
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE spins SET status = ?, updated_at = ?, detail = ? WHERE id = ?")) {
                    ps.setString(1, SpinStatus.RESOLVED.name());
                    ps.setLong(2, now);
                    ps.setString(3, (regrant ? "regranted by " : "dismissed by ") + actor);
                    ps.setString(4, spinId);
                    ps.executeUpdate();
                }
                audit(now, record.player(), spinId, regrant ? "REGRANTED" : "DISMISSED", actor);
                Sql.commit(connection);
                return new Resolution(true, record.player(), record.snapshot());
            }
            if (key.startsWith("P:")) {
                long id;
                try {
                    id = Long.parseLong(key.substring(2));
                } catch (NumberFormatException e) {
                    Sql.rollbackQuietly(connection);
                    return new Resolution(false, null, null);
                }
                UUID player = null;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT player_uuid FROM pending_rewards WHERE id = ? AND status = ?")) {
                    ps.setLong(1, id);
                    ps.setString(2, PendingStatus.UNCERTAIN.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            player = UUID.fromString(rs.getString(1));
                        }
                    }
                }
                if (player == null) {
                    Sql.rollbackQuietly(connection);
                    return new Resolution(false, null, null);
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE pending_rewards SET status = ?, claim_op = NULL, updated_at = ? WHERE id = ?")) {
                    ps.setString(1, regrant ? PendingStatus.PENDING.name() : PendingStatus.RESOLVED.name());
                    ps.setLong(2, now);
                    ps.setLong(3, id);
                    ps.executeUpdate();
                }
                audit(now, player, key, regrant ? "REGRANTED" : "DISMISSED", actor);
                Sql.commit(connection);
                return new Resolution(true, player, null);
            }
            Sql.rollbackQuietly(connection);
            return new Resolution(false, null, null);
        } catch (SQLException | RuntimeException e) {
            Sql.rollbackQuietly(connection);
            throw e;
        }
    }

    // ------------------------------------------------------------------ pending claims

    /**
     * Locks up to {@code limit} pending items for a claim operation (PENDING -> CLAIMING).
     * A second concurrent claim finds nothing to lock.
     */
    public synchronized List<PendingItem> beginClaim(UUID player, String claimOp, int limit, long now) throws SQLException {
        Sql.begin(connection);
        try {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id FROM pending_rewards WHERE player_uuid = ? AND status = ? ORDER BY id LIMIT ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, PendingStatus.PENDING.name());
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            List<PendingItem> claimed = new ArrayList<>();
            for (long id : ids) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE pending_rewards SET status = ?, claim_op = ?, updated_at = ? WHERE id = ? AND status = ?")) {
                    ps.setString(1, PendingStatus.CLAIMING.name());
                    ps.setString(2, claimOp);
                    ps.setLong(3, now);
                    ps.setLong(4, id);
                    ps.setString(5, PendingStatus.PENDING.name());
                    if (ps.executeUpdate() != 1) {
                        continue;
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, player_uuid, spin_id, item_data, amount, status, created_at FROM pending_rewards WHERE id = ?")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            claimed.add(readPending(rs));
                        }
                    }
                }
            }
            Sql.commit(connection);
            return claimed;
        } catch (SQLException | RuntimeException e) {
            Sql.rollbackQuietly(connection);
            throw e;
        }
    }

    /**
     * Completes a claim. {@code remaining} maps pending id to the amount that did not fit
     * (0 = fully claimed). Only rows locked by {@code claimOp} are touched, so a replayed
     * completion has no effect.
     *
     * @return number of rows updated
     */
    public synchronized int completeClaim(String claimOp, Map<Long, Integer> remaining, long now) throws SQLException {
        Sql.begin(connection);
        try {
            int changed = 0;
            for (Map.Entry<Long, Integer> entry : remaining.entrySet()) {
                int left = entry.getValue();
                String sql = left <= 0
                        ? "UPDATE pending_rewards SET status = ?, claim_op = NULL, updated_at = ? WHERE id = ? AND claim_op = ? AND status = ?"
                        : "UPDATE pending_rewards SET status = ?, claim_op = NULL, updated_at = ?, amount = ? WHERE id = ? AND claim_op = ? AND status = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    int i = 1;
                    ps.setString(i++, left <= 0 ? PendingStatus.CLAIMED.name() : PendingStatus.PENDING.name());
                    ps.setLong(i++, now);
                    if (left > 0) {
                        ps.setInt(i++, left);
                    }
                    ps.setLong(i++, entry.getKey());
                    ps.setString(i++, claimOp);
                    ps.setString(i, PendingStatus.CLAIMING.name());
                    changed += ps.executeUpdate();
                }
            }
            Sql.commit(connection);
            return changed;
        } catch (SQLException | RuntimeException e) {
            Sql.rollbackQuietly(connection);
            throw e;
        }
    }

    public synchronized int countPending(UUID player) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM pending_rewards WHERE player_uuid = ? AND status = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, PendingStatus.PENDING.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ------------------------------------------------------------------ player state

    public synchronized PlayerData loadPlayer(UUID player) throws SQLException {
        Long lastDaily = null;
        int bonus = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT last_daily_spin, bonus_spins FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long value = rs.getLong(1);
                    lastDaily = rs.wasNull() ? null : value;
                    bonus = rs.getInt(2);
                }
            }
        }
        boolean active = findActiveSpin(player).isPresent();
        return new PlayerData(player, lastDaily, bonus, countPending(player), active);
    }

    public synchronized List<SpinRecord> findReservedSpins(UUID player) throws SQLException {
        List<SpinRecord> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + SPIN_COLUMNS + " FROM spins WHERE player_uuid = ? AND status = ? ORDER BY created_at")) {
            ps.setString(1, player.toString());
            ps.setString(2, SpinStatus.RESERVED.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readSpin(rs));
                }
            }
        }
        return list;
    }

    public synchronized boolean resetCooldown(UUID player, long now) throws SQLException {
        ensurePlayer(player, now);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE players SET last_daily_spin = NULL, updated_at = ? WHERE uuid = ?")) {
            ps.setLong(1, now);
            ps.setString(2, player.toString());
            boolean ok = ps.executeUpdate() == 1;
            audit(now, player, null, "RESET_COOLDOWN", null);
            return ok;
        }
    }

    /**
     * Adds bonus spins, refusing to exceed {@code max}.
     *
     * @return new bonus balance, or -1 if the cap would be exceeded
     */
    public synchronized int addBonusSpins(UUID player, int amount, int max, long now) throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        Sql.begin(connection);
        try {
            ensurePlayer(player, now);
            int updated;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE players SET bonus_spins = bonus_spins + ?, updated_at = ? WHERE uuid = ? AND bonus_spins + ? <= ?")) {
                ps.setInt(1, amount);
                ps.setLong(2, now);
                ps.setString(3, player.toString());
                ps.setInt(4, amount);
                ps.setInt(5, max);
                updated = ps.executeUpdate();
            }
            if (updated != 1) {
                Sql.rollbackQuietly(connection);
                return -1;
            }
            int balance;
            try (PreparedStatement ps = connection.prepareStatement("SELECT bonus_spins FROM players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    balance = rs.getInt(1);
                }
            }
            audit(now, player, null, "BONUS_ADDED", "amount=" + amount);
            Sql.commit(connection);
            return balance;
        } catch (SQLException | RuntimeException e) {
            Sql.rollbackQuietly(connection);
            throw e;
        }
    }

    public synchronized void audit(long now, UUID player, String ref, String event, String detail) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit_log (ts, player_uuid, ref, event, detail) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, now);
            ps.setString(2, player == null ? null : player.toString());
            ps.setString(3, ref);
            ps.setString(4, event);
            ps.setString(5, detail);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ helpers

    public synchronized Optional<SpinRecord> findSpin(String spinId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT " + SPIN_COLUMNS + " FROM spins WHERE id = ?")) {
            ps.setString(1, spinId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readSpin(rs)) : Optional.empty();
            }
        }
    }

    private Optional<SpinRecord> findActiveSpin(UUID player) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + SPIN_COLUMNS + " FROM spins WHERE player_uuid = ? AND status IN (?, ?) ORDER BY created_at LIMIT 1")) {
            ps.setString(1, player.toString());
            ps.setString(2, SpinStatus.RESERVED.name());
            ps.setString(3, SpinStatus.DELIVERING.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readSpin(rs)) : Optional.empty();
            }
        }
    }

    private boolean spinExists(String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM spins WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void ensurePlayer(UUID player, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO players (uuid, last_daily_spin, bonus_spins, updated_at) VALUES (?, NULL, 0, ?)")) {
            ps.setString(1, player.toString());
            ps.setLong(2, now);
            ps.executeUpdate();
        }
    }

    private void insertPending(UUID player, String spinId, byte[] item, int amount, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pending_rewards (player_uuid, spin_id, item_data, amount, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, spinId);
            ps.setBytes(3, item);
            ps.setInt(4, amount);
            ps.setString(5, PendingStatus.PENDING.name());
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    private static void setBytes(PreparedStatement ps, int index, byte[] value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BLOB);
        } else {
            ps.setBytes(index, value);
        }
    }

    private static SpinRecord readSpin(ResultSet rs) throws SQLException {
        RewardType type = RewardType.valueOf(rs.getString("reward_type"));
        RewardSnapshot snapshot = new RewardSnapshot(
                rs.getString("reward_id"),
                type,
                rs.getBytes("item_data"),
                rs.getInt("item_amount"),
                RewardSnapshot.commandsFromText(rs.getString("commands")),
                rs.getBytes("display_data"),
                rs.getString("display_name"),
                rs.getString("rarity_id"),
                rs.getString("announcement"));
        return new SpinRecord(
                rs.getString("id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("created_at"),
                SpinSource.valueOf(rs.getString("source")),
                SpinStatus.valueOf(rs.getString("status")),
                snapshot);
    }

    private static PendingItem readPending(ResultSet rs) throws SQLException {
        return new PendingItem(
                rs.getLong("id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("spin_id"),
                rs.getBytes("item_data"),
                rs.getInt("amount"),
                PendingStatus.valueOf(rs.getString("status")),
                rs.getLong("created_at"));
    }
}
