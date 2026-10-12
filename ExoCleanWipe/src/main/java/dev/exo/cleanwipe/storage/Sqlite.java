package dev.exo.cleanwipe.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

/** Opens SQLite connections without relying on DriverManager (Paper loads the driver in the plugin class loader). */
public final class Sqlite {

    private Sqlite() {
    }

    public static Connection open(Path file, boolean readOnly) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(10_000);
        if (readOnly) {
            config.setReadOnly(true);
            config.setOpenMode(SQLiteOpenMode.READONLY);
        }
        return config.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
    }

    public static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static List<String> columns(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        // table names come from the integration specs (constants), never from users
        try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(\"" + table.replace("\"", "") + "\")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
        }
        return out;
    }

    public static long count(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    public static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) {
                ps.setObject(i + 1, null);
            } else if (p instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (p instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else if (p instanceof byte[] b) {
                ps.setBytes(i + 1, b);
            } else {
                ps.setString(i + 1, p.toString());
            }
        }
    }

    /** Renders a value as an SQLite literal for restore scripts. */
    public static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2 + 3).append("X'");
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.append('\'').toString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        String text = value.toString();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 0x20) {
                // keeps every restore statement on one line: CAST(X'..' AS TEXT) round-trips UTF-8 exactly
                return "CAST(" + literal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + " AS TEXT)";
            }
        }
        return "'" + text.replace("'", "''") + "'";
    }
}
