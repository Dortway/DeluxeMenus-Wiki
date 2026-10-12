package dev.exo.cleanwipe;

import dev.exo.cleanwipe.storage.Sqlite;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.WipeOptions;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.logging.Logger;

/** Creates databases from the plugins' real DDL (src/test/resources/fixtures, extracted from the installed jars). */
public final class TestDb {

    private TestDb() {
    }

    public static Path create(Path dir, String fixture) throws Exception {
        Path db = dir.resolve(fixture + ".db");
        String sql;
        try (InputStream in = TestDb.class.getResourceAsStream("/fixtures/" + fixture + ".sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = Sqlite.open(db, false); Statement st = c.createStatement()) {
            for (String stmt : sql.split(";\n")) {
                StringBuilder clean = new StringBuilder();
                for (String line : stmt.split("\n")) {
                    if (!line.startsWith("--")) {
                        clean.append(line).append('\n');
                    }
                }
                String s = clean.toString().trim();
                if (!s.isEmpty()) {
                    st.execute(s);
                }
            }
        }
        return db;
    }

    public static void exec(Path db, String sql, Object... params) throws Exception {
        try (Connection c = Sqlite.open(db, false)) {
            Sqlite.update(c, sql, params);
        }
    }

    public static long count(Path db, String sql, Object... params) throws Exception {
        try (Connection c = Sqlite.open(db, false)) {
            return Sqlite.count(c, sql, params);
        }
    }

    public static String text(Path db, String sql, Object... params) throws Exception {
        try (Connection c = Sqlite.open(db, false); var ps = c.prepareStatement(sql)) {
            Sqlite.bind(ps, params);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public static StepContext ctx(Path backup) {
        return new StepContext(1, backup, Logger.getLogger("test"), 1_700_000_000_000L, WipeOptions.DEFAULT);
    }
}
