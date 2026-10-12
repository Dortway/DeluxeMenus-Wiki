package dev.exo.cleanwipe.integrations.sql;

import dev.exo.cleanwipe.storage.Sqlite;
import dev.exo.cleanwipe.wipe.Backups;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StartupIntegration;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wipes rows of another plugin's SQLite database during server start (before that plugin loads it).
 *
 * <p>For each change the affected rows are first exported as {@code INSERT OR REPLACE} statements to
 * {@code restore.sql}; the database file itself is also copied once per job. Changes run in one
 * {@code BEGIN IMMEDIATE} transaction, so a crash leaves either the old or the new state.
 */
public abstract class SqlStartupIntegration implements StartupIntegration {

    /**
     * One table change. {@code set == null} deletes the matching rows, otherwise they are updated.
     * {@code where} and {@code set} only contain constant SQL written in this code base plus {@code ?} parameters.
     */
    public record Change(String label, String table, String where, List<Object> params, String set) {
    }

    /** {@code blockers}: why the step must not run (unfinished transactions involving other parties). */
    public record Plan(List<Change> changes, List<String> blockers, List<String> notes) {
    }

    protected final String id;
    protected final Path database;

    protected SqlStartupIntegration(String id, Path database) {
        this.id = id;
        this.database = database;
    }

    @Override
    public String id() {
        return id;
    }

    /** Tables that must exist; otherwise the schema is not the one this integration was written for. */
    protected abstract List<String> requiredTables();

    /** @param target the player, or {@code null} for a season reset of everyone */
    protected abstract Plan plan(Connection c, UUID target, long now) throws SQLException;

    /** Extra staff-facing preview lines (for example coordinates of placed blocks that remain). */
    protected void describe(Connection c, UUID target, Preview preview) throws SQLException {
    }

    @Override
    public Availability availability() {
        if (!Files.exists(database)) {
            return Availability.missing("no database at " + database + " (nothing stored yet)");
        }
        return Availability.ok();
    }

    private String checkSchema(Connection c) throws SQLException {
        List<String> missing = new ArrayList<>();
        for (String t : requiredTables()) {
            if (!Sqlite.tableExists(c, t)) {
                missing.add(t);
            }
        }
        return missing.isEmpty() ? null : "unexpected schema in " + database.getFileName() + ": missing table(s) " + String.join(", ", missing);
    }

    private Connection openForPreview() throws SQLException {
        try {
            return Sqlite.open(database, true);
        } catch (SQLException e) {
            Connection c = Sqlite.open(database, false);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA query_only=ON");
            }
            return c;
        }
    }

    @Override
    public Preview preview(UUID target) throws Exception {
        return doPreview(target);
    }

    @Override
    public Preview previewAll() throws Exception {
        return doPreview(null);
    }

    private Preview doPreview(UUID target) throws SQLException {
        Preview p = new Preview();
        if (!Files.exists(database)) {
            return p;
        }
        try (Connection c = openForPreview()) {
            String schema = checkSchema(c);
            if (schema != null) {
                return p.block(schema);
            }
            Plan plan = plan(c, target, System.currentTimeMillis());
            for (Change ch : plan.changes()) {
                long n = Sqlite.count(c, "SELECT COUNT(*) FROM " + ch.table() + " WHERE " + ch.where(), ch.params().toArray());
                if (n > 0) {
                    p.line(ch.label(), n);
                }
            }
            if (target != null) {
                plan.blockers().forEach(p::block);
            } else {
                plan.blockers().forEach(b -> p.warn(b + " (left as is by a season reset)"));
            }
            plan.notes().forEach(p::warn);
            describe(c, target, p);
        }
        return p;
    }

    @Override
    public StepResult wipe(StepContext ctx, UUID target) throws Exception {
        return run(ctx, target);
    }

    @Override
    public StepResult wipeAll(StepContext ctx) throws Exception {
        return run(ctx, null);
    }

    private StepResult run(StepContext ctx, UUID target) throws Exception {
        if (!Files.exists(database)) {
            return StepResult.nothing("no database file");
        }
        Path dir = ctx.backupDirCreated();
        // whole-file copy first, while no connection is open (the owning plugin has not started yet)
        Backups.copyOnce(database, dir.resolve(database.getFileName() + ".before"));
        Backups.copyOnce(database.resolveSibling(database.getFileName() + "-wal"), dir.resolve(database.getFileName() + "-wal.before"));

        try (Connection c = Sqlite.open(database, false)) {
            String schema = checkSchema(c);
            if (schema != null) {
                return StepResult.failed(schema);
            }
            Plan plan = plan(c, target, ctx.now());
            if (target != null && !plan.blockers().isEmpty()) {
                return StepResult.blocked(String.join("; ", plan.blockers()));
            }
            try (Statement st = c.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            }
            try {
                StringBuilder script = new StringBuilder();
                script.append("-- exocleanwipe restore script for ").append(id).append(", job ").append(ctx.jobId())
                        .append(", target ").append(target == null ? "everyone" : target).append('\n')
                        .append("-- apply with the server stopped: sqlite3 ").append(database.getFileName())
                        .append(" < restore.sql  (or /wipe restore ").append(ctx.jobId()).append(")\n")
                        .append("BEGIN;\n");
                List<String> summary = new ArrayList<>();
                long total = 0;
                for (Change ch : plan.changes()) {
                    exportRows(c, ch, script);
                }
                script.append("COMMIT;\n");
                boolean earlierAttempt = Files.exists(dir.resolve("restore.sql"));
                Backups.writeOnce(dir.resolve("restore.sql"), script.toString().getBytes(StandardCharsets.UTF_8));
                for (Change ch : plan.changes()) {
                    String sql = ch.set() == null
                            ? "DELETE FROM " + ch.table() + " WHERE " + ch.where()
                            : "UPDATE " + ch.table() + " SET " + ch.set() + " WHERE " + ch.where();
                    int n = Sqlite.update(c, sql, ch.params().toArray());
                    total += n;
                    if (n > 0) {
                        summary.add(ch.label() + " " + n);
                    }
                }
                try (Statement st = c.createStatement()) {
                    st.execute("COMMIT");
                }
                if (total == 0) {
                    return earlierAttempt
                            ? StepResult.done("already applied by an earlier attempt (backup kept)")
                            : StepResult.nothing("nothing stored");
                }
                return StepResult.done(String.join(", ", summary));
            } catch (Exception e) {
                try (Statement st = c.createStatement()) {
                    st.execute("ROLLBACK");
                } catch (SQLException ignored) {
                    // connection already rolled back
                }
                throw e;
            }
        }
    }

    private static void exportRows(Connection c, Change ch, StringBuilder out) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + ch.table() + " WHERE " + ch.where())) {
            Sqlite.bind(ps, ch.params().toArray());
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                StringBuilder cols = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    cols.append(i > 1 ? ", " : "").append('"').append(md.getColumnName(i)).append('"');
                }
                while (rs.next()) {
                    out.append("INSERT OR REPLACE INTO \"").append(ch.table()).append("\" (").append(cols).append(") VALUES (");
                    for (int i = 1; i <= n; i++) {
                        out.append(i > 1 ? ", " : "").append(Sqlite.literal(rs.getObject(i)));
                    }
                    out.append(");\n");
                }
            }
        }
    }

    @Override
    public StepResult restore(StepContext ctx, Path source) throws Exception {
        Path script = source.resolve("restore.sql");
        if (!Files.exists(script)) {
            return StepResult.nothing("no restore.sql in " + source);
        }
        if (!Files.exists(database)) {
            return StepResult.failed("database " + database + " does not exist any more");
        }
        Path dir = ctx.backupDirCreated();
        Backups.copyOnce(database, dir.resolve(database.getFileName() + ".before-restore"));
        String sql = Files.readString(script, StandardCharsets.UTF_8);
        int statements = 0;
        try (Connection c = Sqlite.open(database, false)) {
            try (Statement st = c.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
                try {
                    for (String line : sql.split("\n")) {
                        String s = line.trim();
                        if (s.startsWith("INSERT OR REPLACE INTO ")) {
                            st.execute(s.endsWith(";") ? s.substring(0, s.length() - 1) : s);
                            statements++;
                        }
                    }
                    st.execute("COMMIT");
                } catch (SQLException e) {
                    st.execute("ROLLBACK");
                    throw e;
                }
            }
        }
        return statements == 0 ? StepResult.nothing("restore script had no rows") : StepResult.done("restored " + statements + " row(s)");
    }

    protected static List<Object> params(UUID target, int count) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(target.toString());
        }
        return list;
    }
}
