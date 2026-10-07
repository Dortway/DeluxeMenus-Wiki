package dev.exo.dailyspinner.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Versioned schema migrations. Each migration runs once, inside its own transaction. */
public final class Migrations {

    private record Migration(int version, String description, List<String> statements) {
    }

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "initial schema", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS players (
                        uuid            TEXT    PRIMARY KEY,
                        last_daily_spin INTEGER NULL,
                        bonus_spins     INTEGER NOT NULL DEFAULT 0 CHECK (bonus_spins >= 0),
                        updated_at      INTEGER NOT NULL
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS spins (
                        id            TEXT    PRIMARY KEY,
                        player_uuid   TEXT    NOT NULL,
                        created_at    INTEGER NOT NULL,
                        updated_at    INTEGER NOT NULL,
                        source        TEXT    NOT NULL,
                        status        TEXT    NOT NULL,
                        reward_id     TEXT    NOT NULL,
                        reward_type   TEXT    NOT NULL,
                        item_data     BLOB    NULL,
                        item_amount   INTEGER NOT NULL DEFAULT 0,
                        commands      TEXT    NULL,
                        display_data  BLOB    NULL,
                        display_name  TEXT    NOT NULL,
                        rarity_id     TEXT    NOT NULL,
                        announcement  TEXT    NULL,
                        detail        TEXT    NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_spins_player_status ON spins (player_uuid, status)",
                    "CREATE INDEX IF NOT EXISTS idx_spins_status ON spins (status)",
                    """
                    CREATE TABLE IF NOT EXISTS pending_rewards (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT    NOT NULL,
                        spin_id     TEXT    NULL,
                        item_data   BLOB    NOT NULL,
                        amount      INTEGER NOT NULL CHECK (amount > 0),
                        status      TEXT    NOT NULL,
                        claim_op    TEXT    NULL,
                        created_at  INTEGER NOT NULL,
                        updated_at  INTEGER NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_pending_player_status ON pending_rewards (player_uuid, status)",
                    """
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        ts          INTEGER NOT NULL,
                        player_uuid TEXT    NULL,
                        ref         TEXT    NULL,
                        event       TEXT    NOT NULL,
                        detail      TEXT    NULL
                    )"""
            ))
    );

    private Migrations() {
    }

    public static int latestVersion() {
        return MIGRATIONS.get(MIGRATIONS.size() - 1).version();
    }

    /** @return the schema version after migrating */
    public static int migrate(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL, applied_at INTEGER NOT NULL)");
        }
        int current = currentVersion(connection);
        if (current > latestVersion()) {
            throw new SQLException("Database schema version " + current
                    + " is newer than this plugin supports (" + latestVersion() + "). Refusing to start.");
        }
        for (Migration migration : MIGRATIONS) {
            if (migration.version() <= current) {
                continue;
            }
            Sql.begin(connection);
            try (Statement st = connection.createStatement()) {
                for (String sql : migration.statements()) {
                    st.execute(sql);
                }
                st.execute("INSERT INTO schema_version (version, applied_at) VALUES ("
                        + migration.version() + ", " + System.currentTimeMillis() + ")");
                Sql.commit(connection);
            } catch (SQLException e) {
                Sql.rollbackQuietly(connection);
                throw new SQLException("Migration " + migration.version() + " (" + migration.description() + ") failed", e);
            }
            current = migration.version();
        }
        return current;
    }

    public static int currentVersion(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
