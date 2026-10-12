package dev.exo.cleanwipe;

import dev.exo.cleanwipe.storage.Sqlite;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Inserts test rows into a fixture database. Columns not given are filled with a value of the declared type when they
 * are NOT NULL without a default, so tests only spell out what matters to them.
 */
public final class Rows {

    private Rows() {
    }

    public static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static void insert(Path db, String table, Map<String, Object> values) throws Exception {
        try (Connection c = Sqlite.open(db, false)) {
            Map<String, Object> row = new LinkedHashMap<>(values);
            try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(\"" + table + "\")"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    boolean notNull = rs.getInt("notnull") == 1;
                    boolean pk = rs.getInt("pk") > 0;
                    String type = String.valueOf(rs.getString("type")).toUpperCase(Locale.ROOT);
                    if (row.containsKey(name) || rs.getString("dflt_value") != null) {
                        continue;
                    }
                    if (pk && type.contains("INT")) {
                        continue; // autoincrement / rowid
                    }
                    if (notNull || pk) {
                        row.put(name, type.contains("INT") ? (Object) 1L
                                : type.contains("REAL") || type.contains("DOUBLE") ? (Object) "1.0"
                                : type.contains("BLOB") ? (Object) new byte[]{1, 2, 3}
                                : (Object) (name + "-" + System.nanoTime()));
                    }
                }
            }
            List<String> cols = new ArrayList<>(row.keySet());
            String sql = "INSERT INTO \"" + table + "\" (" + String.join(", ", cols.stream().map(x -> "\"" + x + "\"").toList())
                    + ") VALUES (" + "?,".repeat(cols.size() - 1) + "?)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Sqlite.bind(ps, row.values().toArray());
                ps.executeUpdate();
            }
        }
    }
}
