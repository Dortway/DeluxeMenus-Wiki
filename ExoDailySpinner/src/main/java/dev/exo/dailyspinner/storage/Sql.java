package dev.exo.dailyspinner.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Explicit SQLite transaction helpers (connections run in auto-commit mode between transactions). */
final class Sql {

    private Sql() {
    }

    /** Starts a write transaction, taking SQLite's RESERVED lock immediately to serialise writers. */
    static void begin(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
        }
    }

    static void commit(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("COMMIT");
        }
    }

    static void rollbackQuietly(Connection connection) {
        try (Statement st = connection.createStatement()) {
            st.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // No transaction active.
        }
    }
}
