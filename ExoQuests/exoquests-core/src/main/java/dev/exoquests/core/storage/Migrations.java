package dev.exoquests.core.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * Ordered, forward-only schema migrations. Each migration runs in its own transaction together with
 * the version bump, so a crash mid-migration leaves the previous version intact.
 */
public final class Migrations {

    private static final List<List<String>> MIGRATIONS = List.of(
            // Version 1: initial schema.
            List.of(
                    """
                    CREATE TABLE players (
                        uuid        TEXT PRIMARY KEY,
                        points      INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0),
                        updated_at  INTEGER NOT NULL
                    )""",
                    """
                    CREATE TABLE point_ledger (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid           TEXT NOT NULL,
                        delta          INTEGER NOT NULL,
                        balance_after  INTEGER NOT NULL CHECK (balance_after >= 0),
                        reason         TEXT NOT NULL,
                        ref_id         TEXT NOT NULL UNIQUE,
                        actor          TEXT NOT NULL,
                        created_at     INTEGER NOT NULL
                    )""",
                    "CREATE INDEX idx_ledger_uuid ON point_ledger(uuid, created_at)",
                    """
                    CREATE TABLE daily_assignments (
                        uuid           TEXT NOT NULL,
                        period         TEXT NOT NULL,
                        slot           INTEGER NOT NULL CHECK (slot BETWEEN 0 AND 2),
                        quest_id       TEXT NOT NULL,
                        target         INTEGER NOT NULL CHECK (target > 0),
                        reward         INTEGER NOT NULL CHECK (reward > 0),
                        progress       INTEGER NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= target),
                        assigned_at    INTEGER NOT NULL,
                        completed_at   INTEGER,
                        completion_id  TEXT UNIQUE,
                        PRIMARY KEY (uuid, period, slot),
                        UNIQUE (uuid, period, quest_id)
                    )""",
                    "CREATE INDEX idx_assignments_period ON daily_assignments(period)",
                    """
                    CREATE TABLE quest_completions (
                        completion_id  TEXT PRIMARY KEY,
                        uuid           TEXT NOT NULL,
                        period         TEXT NOT NULL,
                        slot           INTEGER NOT NULL,
                        quest_id       TEXT NOT NULL,
                        reward         INTEGER NOT NULL,
                        credited       INTEGER NOT NULL,
                        completed_at   INTEGER NOT NULL
                    )""",
                    "CREATE INDEX idx_completions_uuid ON quest_completions(uuid, period)",
                    """
                    CREATE TABLE purchases (
                        purchase_id      TEXT PRIMARY KEY,
                        uuid             TEXT NOT NULL,
                        item_id          TEXT NOT NULL,
                        price            INTEGER NOT NULL CHECK (price > 0),
                        revision         TEXT NOT NULL,
                        reward_type      TEXT NOT NULL,
                        reward_snapshot  TEXT NOT NULL,
                        state            TEXT NOT NULL CHECK (state IN
                            ('PENDING','DELIVERING','DELIVERED','REFUNDED','NEEDS_REVIEW','RESOLVED')),
                        note             TEXT,
                        created_at       INTEGER NOT NULL,
                        updated_at       INTEGER NOT NULL
                    )""",
                    "CREATE INDEX idx_purchases_state ON purchases(state)",
                    "CREATE INDEX idx_purchases_uuid ON purchases(uuid, state)",
                    """
                    CREATE TABLE placed_blocks (
                        world      TEXT NOT NULL,
                        x          INTEGER NOT NULL,
                        y          INTEGER NOT NULL,
                        z          INTEGER NOT NULL,
                        material   TEXT NOT NULL,
                        kind       INTEGER NOT NULL,
                        owner      TEXT,
                        placed_at  INTEGER NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    ) WITHOUT ROWID""",
                    """
                    CREATE TABLE audit_log (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        actor       TEXT NOT NULL,
                        action      TEXT NOT NULL,
                        target      TEXT,
                        details     TEXT,
                        created_at  INTEGER NOT NULL
                    )"""
            )
    );

    private Migrations() {
    }

    public static int latestVersion() {
        return MIGRATIONS.size();
    }

    static void apply(Connection c, Logger logger) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            c.commit();
        }
        int current = currentVersion(c);
        if (current > MIGRATIONS.size()) {
            throw new SQLException("database schema version " + current
                    + " is newer than this ExoQuests build supports (" + MIGRATIONS.size() + ")");
        }
        for (int v = current; v < MIGRATIONS.size(); v++) {
            try (Statement st = c.createStatement()) {
                for (String sql : MIGRATIONS.get(v)) {
                    st.execute(sql);
                }
                st.executeUpdate("DELETE FROM schema_version");
                st.executeUpdate("INSERT INTO schema_version(version) VALUES (" + (v + 1) + ")");
                c.commit();
                logger.info("Applied database migration " + (v + 1));
            } catch (SQLException e) {
                c.rollback();
                throw new SQLException("migration " + (v + 1) + " failed: " + e.getMessage(), e);
            }
        }
    }

    static int currentVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            int v = rs.next() ? rs.getInt(1) : 0;
            c.commit();
            return v;
        }
    }
}
