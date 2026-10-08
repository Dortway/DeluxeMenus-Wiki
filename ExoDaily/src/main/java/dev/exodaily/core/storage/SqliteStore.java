package dev.exodaily.core.storage;

import dev.exodaily.core.progression.PlayerProfile;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.reward.RewardSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite persistence. One connection is used under a lock, so every transaction is serialized;
 * uniqueness of claims is additionally enforced by UNIQUE constraints. All statements are
 * prepared. Methods block and must be called off the server thread.
 */
public final class SqliteStore implements AutoCloseable {

    /** Current schema version; migrations bring older databases up to this version. */
    public static final int SCHEMA_VERSION = 1;

    private static final List<String> MIGRATION_1 = List.of(
            """
            CREATE TABLE players (
                uuid TEXT PRIMARY KEY NOT NULL,
                last_name TEXT,
                first_use_date TEXT NOT NULL,
                cycle_number INTEGER NOT NULL CHECK (cycle_number >= 1),
                cycle_start_date TEXT NOT NULL,
                cycle_length INTEGER NOT NULL CHECK (cycle_length >= 1),
                updated_at INTEGER NOT NULL
            )""",
            "CREATE INDEX players_name ON players (last_name COLLATE NOCASE)",
            """
            CREATE TABLE assignments (
                uuid TEXT NOT NULL,
                cycle INTEGER NOT NULL,
                day INTEGER NOT NULL,
                position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 3),
                reward_date TEXT NOT NULL,
                pool_id TEXT NOT NULL,
                reward_id TEXT NOT NULL,
                snapshot TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, cycle, day, position)
            )""",
            """
            CREATE TABLE claims (
                claim_id INTEGER PRIMARY KEY AUTOINCREMENT,
                claim_key TEXT NOT NULL UNIQUE,
                uuid TEXT NOT NULL,
                cycle INTEGER NOT NULL,
                day INTEGER NOT NULL,
                position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 3),
                reward_date TEXT NOT NULL,
                reward_id TEXT NOT NULL,
                state TEXT NOT NULL CHECK (state IN ('RESERVED', 'DELIVERING', 'DELIVERED', 'UNCERTAIN')),
                attempt_id TEXT NOT NULL,
                reserved_at INTEGER NOT NULL,
                delivering_at INTEGER,
                delivered_at INTEGER,
                note TEXT,
                UNIQUE (uuid, cycle, day, position)
            )""",
            "CREATE INDEX claims_state ON claims (state)",
            "CREATE INDEX claims_date ON claims (uuid, reward_date)",
            """
            CREATE TABLE audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                actor TEXT NOT NULL,
                action TEXT NOT NULL,
                target TEXT NOT NULL,
                details TEXT NOT NULL
            )"""
    );

    private static final List<List<String>> MIGRATIONS = List.of(MIGRATION_1);

    private final Path file;
    private final int busyTimeoutMs;
    private final Logger logger;
    private final ReentrantLock lock = new ReentrantLock();
    private Connection connection;
    private volatile boolean available;

    public SqliteStore(Path file, int busyTimeoutMs, Logger logger) {
        this.file = file;
        this.busyTimeoutMs = busyTimeoutMs;
        this.logger = logger;
    }

    /** Opens the database, creating or migrating the schema. */
    public void open() {
        lock.lock();
        try {
            if (connection != null) {
                return;
            }
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new StorageException("SQLite JDBC driver (org.sqlite.JDBC) is not available on this server", e);
            }
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Connection opened = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                try (Statement statement = opened.createStatement()) {
                    statement.execute("PRAGMA busy_timeout = " + Math.max(0, busyTimeoutMs));
                    statement.execute("PRAGMA journal_mode = WAL");
                    // FULL makes every committed claim state durable before the call returns.
                    statement.execute("PRAGMA synchronous = FULL");
                    statement.execute("PRAGMA foreign_keys = ON");
                }
                migrate(opened);
                connection = opened;
                available = true;
            } catch (Exception e) {
                available = false;
                throw new StorageException("could not open SQLite database " + file + ": " + e.getMessage(), e);
            }
        } finally {
            lock.unlock();
        }
    }

    private void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        int version = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
            if (rs.next()) {
                version = rs.getInt(1);
            }
        }
        if (version > SCHEMA_VERSION) {
            throw new SQLException("database schema version " + version + " is newer than this plugin supports ("
                    + SCHEMA_VERSION + "); refusing to use it");
        }
        for (int target = version + 1; target <= SCHEMA_VERSION; target++) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String sql : MIGRATIONS.get(target - 1)) {
                    statement.execute(sql);
                }
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO schema_version (version) VALUES (?)")) {
                    insert.setInt(1, target);
                    insert.executeUpdate();
                }
                connection.commit();
                logger.info("Database schema migrated to version " + target);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public boolean isAvailable() {
        return available;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            available = false;
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "Error while closing the database", e);
                }
                connection = null;
            }
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    public interface TxWork<T> {
        T run(Tx tx) throws SQLException;
    }

    /** Runs {@code work} in a single transaction; any failure rolls back and surfaces as {@link StorageException}. */
    public <T> T transaction(TxWork<T> work) {
        lock.lock();
        try {
            if (connection == null) {
                throw new StorageException("storage is not available");
            }
            try {
                connection.setAutoCommit(false);
                try {
                    T result = work.run(new Tx(connection));
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    rollbackQuietly();
                    throw e;
                } finally {
                    restoreAutoCommit();
                }
            } catch (SQLException e) {
                throw new StorageException("database operation failed: " + e.getMessage(), e);
            }
        } finally {
            lock.unlock();
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Rollback failed", e);
        }
    }

    private void restoreAutoCommit() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not restore auto-commit", e);
        }
    }

    /** Typed, prepared-statement operations available inside a transaction. */
    public static final class Tx {

        private final Connection c;

        private Tx(Connection connection) {
            this.c = connection;
        }

        // ---------------------------------------------------------------- players

        public Optional<PlayerProfile> profile(UUID uuid) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT first_use_date, cycle_number, cycle_start_date, cycle_length FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new PlayerProfile(uuid, LocalDate.parse(rs.getString(1)), rs.getInt(2),
                            LocalDate.parse(rs.getString(3)), rs.getInt(4)));
                }
            }
        }

        public Optional<UUID> findByName(String name) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid FROM players WHERE last_name = ? COLLATE NOCASE ORDER BY updated_at DESC LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
                }
            }
        }

        public Optional<String> lastName(UUID uuid) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_name FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
                }
            }
        }

        public void saveProfile(PlayerProfile profile, String lastName, long now) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO players (uuid, last_name, first_use_date, cycle_number, cycle_start_date, cycle_length, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (uuid) DO UPDATE SET
                        last_name = COALESCE(excluded.last_name, players.last_name),
                        cycle_number = excluded.cycle_number,
                        cycle_start_date = excluded.cycle_start_date,
                        cycle_length = excluded.cycle_length,
                        updated_at = excluded.updated_at""")) {
                ps.setString(1, profile.uuid().toString());
                ps.setString(2, lastName);
                ps.setString(3, profile.firstUseDate().toString());
                ps.setInt(4, profile.cycleNumber());
                ps.setString(5, profile.cycleStart().toString());
                ps.setInt(6, profile.cycleLength());
                ps.setLong(7, now);
                ps.executeUpdate();
            }
        }

        // ---------------------------------------------------------------- assignments

        public Map<RewardPosition, AssignmentRecord> assignments(UUID uuid, int cycle, int day) throws SQLException {
            Map<RewardPosition, AssignmentRecord> result = new EnumMap<>(RewardPosition.class);
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT position, reward_date, pool_id, snapshot, created_at FROM assignments
                    WHERE uuid = ? AND cycle = ? AND day = ?""")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, cycle);
                ps.setInt(3, day);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        RewardPosition position = position(rs.getInt(1));
                        result.put(position, new AssignmentRecord(uuid, cycle, day, position,
                                LocalDate.parse(rs.getString(2)), rs.getString(3),
                                RewardSnapshot.decode(rs.getString(4)), rs.getLong(5)));
                    }
                }
            }
            return result;
        }

        /** Inserts an assignment unless one already exists; existing assignments are never replaced. */
        public boolean insertAssignment(AssignmentRecord record) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT OR IGNORE INTO assignments (uuid, cycle, day, position, reward_date, pool_id, reward_id, snapshot, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, record.uuid().toString());
                ps.setInt(2, record.cycle());
                ps.setInt(3, record.day());
                ps.setInt(4, record.position().number());
                ps.setString(5, record.rewardDate().toString());
                ps.setString(6, record.poolId());
                ps.setString(7, record.reward().id());
                ps.setString(8, RewardSnapshot.encode(record.reward()));
                ps.setLong(9, record.createdAt());
                return ps.executeUpdate() == 1;
            }
        }

        // ---------------------------------------------------------------- claims

        private static final String CLAIM_COLUMNS = """
                claim_id, uuid, cycle, day, position, reward_date, reward_id, state, attempt_id,
                reserved_at, delivering_at, delivered_at, note""";

        public Map<RewardPosition, ClaimRecord> claims(UUID uuid, int cycle, int day) throws SQLException {
            Map<RewardPosition, ClaimRecord> result = new EnumMap<>(RewardPosition.class);
            try (PreparedStatement ps = c.prepareStatement("SELECT " + CLAIM_COLUMNS
                    + " FROM claims WHERE uuid = ? AND cycle = ? AND day = ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, cycle);
                ps.setInt(3, day);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ClaimRecord record = readClaim(rs);
                        result.put(record.position(), record);
                    }
                }
            }
            return result;
        }

        public Optional<ClaimRecord> claim(ClaimKey key) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + CLAIM_COLUMNS + " FROM claims WHERE claim_key = ?")) {
                ps.setString(1, key.asString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readClaim(rs)) : Optional.empty();
                }
            }
        }

        public Optional<ClaimRecord> claimById(long id) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + CLAIM_COLUMNS + " FROM claims WHERE claim_id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readClaim(rs)) : Optional.empty();
                }
            }
        }

        public List<ClaimRecord> claimsInState(ClaimState state, UUID uuid, int limit) throws SQLException {
            String sql = "SELECT " + CLAIM_COLUMNS + " FROM claims WHERE state = ?"
                    + (uuid != null ? " AND uuid = ?" : "") + " ORDER BY claim_id LIMIT ?";
            List<ClaimRecord> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, state.name());
                if (uuid != null) {
                    ps.setString(i++, uuid.toString());
                }
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(readClaim(rs));
                    }
                }
            }
            return result;
        }

        public int countInState(ClaimState state) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM claims WHERE state = ?")) {
                ps.setString(1, state.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }

        /**
         * Reserves a claim. Returns false when a claim with the same identity already exists;
         * the UNIQUE constraints make this the single source of truth for duplicate prevention.
         */
        public boolean insertReservation(ClaimKey key, LocalDate rewardDate, String rewardId, String attemptId, long now)
                throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT OR IGNORE INTO claims (claim_key, uuid, cycle, day, position, reward_date, reward_id, state, attempt_id, reserved_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?)""")) {
                ps.setString(1, key.asString());
                ps.setString(2, key.uuid().toString());
                ps.setInt(3, key.cycle());
                ps.setInt(4, key.day());
                ps.setInt(5, key.position().number());
                ps.setString(6, rewardDate.toString());
                ps.setString(7, rewardId);
                ps.setString(8, attemptId);
                ps.setLong(9, now);
                return ps.executeUpdate() == 1;
            }
        }

        /** Moves a claim of this attempt from {@code from} to {@code to}; returns false if nothing matched. */
        public boolean transition(ClaimKey key, String attemptId, ClaimState from, ClaimState to, long now, String note)
                throws SQLException {
            String timestampColumn = switch (to) {
                case DELIVERING -> "delivering_at";
                case DELIVERED -> "delivered_at";
                default -> null;
            };
            String sql = "UPDATE claims SET state = ?, note = COALESCE(?, note)"
                    + (timestampColumn != null ? ", " + timestampColumn + " = ?" : "")
                    + " WHERE claim_key = ? AND attempt_id = ? AND state = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, to.name());
                ps.setString(i++, note);
                if (timestampColumn != null) {
                    ps.setLong(i++, now);
                }
                ps.setString(i++, key.asString());
                ps.setString(i++, attemptId);
                ps.setString(i, from.name());
                return ps.executeUpdate() == 1;
            }
        }

        /** Deletes a claim of this attempt that is still in {@code from}; only used when nothing was given. */
        public boolean delete(ClaimKey key, String attemptId, ClaimState from) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM claims WHERE claim_key = ? AND attempt_id = ? AND state = ?")) {
                ps.setString(1, key.asString());
                ps.setString(2, attemptId);
                ps.setString(3, from.name());
                return ps.executeUpdate() == 1;
            }
        }

        /** Administrative resolution of an uncertain claim by id. */
        public boolean resolveUncertain(long claimId, boolean delivered, long now, String note) throws SQLException {
            String sql = delivered
                    ? "UPDATE claims SET state = 'DELIVERED', delivered_at = COALESCE(delivered_at, ?), note = ? WHERE claim_id = ? AND state = 'UNCERTAIN'"
                    : "DELETE FROM claims WHERE claim_id = ? AND state = 'UNCERTAIN'";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (delivered) {
                    ps.setLong(1, now);
                    ps.setString(2, note);
                    ps.setLong(3, claimId);
                } else {
                    ps.setLong(1, claimId);
                }
                return ps.executeUpdate() == 1;
            }
        }

        /** Startup recovery: reservations that never reached DELIVERING gave nothing and are removed. */
        public int deleteAllReserved() throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM claims WHERE state = 'RESERVED'")) {
                return ps.executeUpdate();
            }
        }

        /** Startup recovery: claims interrupted during delivery become UNCERTAIN, never reissued. */
        public int flagDeliveringUncertain(String note) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE claims SET state = 'UNCERTAIN', note = ? WHERE state = 'DELIVERING'")) {
                ps.setString(1, note);
                return ps.executeUpdate();
            }
        }

        public int countClaimsOnDate(UUID uuid, LocalDate date) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM claims WHERE uuid = ? AND reward_date = ? AND state <> 'RESERVED'")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, date.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }

        /** Number of non-transient claims per day of a cycle. */
        public Map<Integer, Integer> claimCountsByDay(UUID uuid, int cycle) throws SQLException {
            Map<Integer, Integer> result = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT day, COUNT(*) FROM claims WHERE uuid = ? AND cycle = ? AND state <> 'RESERVED' GROUP BY day")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, cycle);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getInt(1), rs.getInt(2));
                    }
                }
            }
            return result;
        }

        // ---------------------------------------------------------------- audit

        public void audit(AuditEntry entry) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_log (at, actor, action, target, details) VALUES (?, ?, ?, ?, ?)")) {
                ps.setLong(1, entry.at());
                ps.setString(2, entry.actor());
                ps.setString(3, entry.action());
                ps.setString(4, entry.target());
                ps.setString(5, entry.details());
                ps.executeUpdate();
            }
        }

        public List<AuditEntry> recentAudit(int limit) throws SQLException {
            List<AuditEntry> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT at, actor, action, target, details FROM audit_log ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new AuditEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                    }
                }
            }
            return result;
        }

        private static ClaimRecord readClaim(ResultSet rs) throws SQLException {
            long deliveringAt = rs.getLong(11);
            Long delivering = rs.wasNull() ? null : deliveringAt;
            long deliveredAt = rs.getLong(12);
            Long delivered = rs.wasNull() ? null : deliveredAt;
            return new ClaimRecord(
                    rs.getLong(1),
                    UUID.fromString(rs.getString(2)),
                    rs.getInt(3),
                    rs.getInt(4),
                    position(rs.getInt(5)),
                    LocalDate.parse(rs.getString(6)),
                    rs.getString(7),
                    ClaimState.valueOf(rs.getString(8)),
                    rs.getString(9),
                    rs.getLong(10),
                    delivering,
                    delivered,
                    rs.getString(13));
        }

        private static RewardPosition position(int number) throws SQLException {
            return RewardPosition.fromNumber(number)
                    .orElseThrow(() -> new SQLException("invalid reward position " + number));
        }
    }
}
