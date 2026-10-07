package dev.exoquests.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Append-only administrator audit trail. */
public final class AuditStore {

    private AuditStore() {
    }

    public static void record(Connection c, String actor, String action, String target, String details, long now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log(actor, action, target, details, created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, actor);
            ps.setString(2, action);
            ps.setString(3, target);
            ps.setString(4, details);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }
}
