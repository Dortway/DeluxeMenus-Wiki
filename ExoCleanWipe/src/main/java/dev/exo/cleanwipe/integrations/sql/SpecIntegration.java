package dev.exo.cleanwipe.integrations.sql;

import dev.exo.cleanwipe.storage.Sqlite;
import dev.exo.cleanwipe.wipe.Preview;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A database integration described entirely by constant SQL fragments. In {@code playerWhere} every {@code ?} is
 * bound to the target UUID; {@code allWhere} is used for a season reset and has no parameters. {@code {now}} in a
 * SET clause is replaced by the current epoch milliseconds.
 */
public final class SpecIntegration extends SqlStartupIntegration {

    public record Rule(String label, String table, String playerWhere, String allWhere, String set) {
    }

    public record Blocker(String label, String table, String playerWhere) {
    }

    /** Optional: SELECT world, x, y, z ... WHERE (one ?) listing blocks that stay in the world. */
    public record Placed(String label, String sql) {
    }

    private final String covers;
    private final List<String> tables;
    private final List<Blocker> blockers;
    private final List<Rule> rules;
    private final Placed placed;

    public SpecIntegration(String id, Path database, String covers, List<String> tables, List<Blocker> blockers,
                           List<Rule> rules, Placed placed) {
        super(id, database);
        this.covers = covers;
        this.tables = tables;
        this.blockers = blockers;
        this.rules = rules;
        this.placed = placed;
    }

    public static Rule delete(String label, String table, String playerWhere, String allWhere) {
        return new Rule(label, table, playerWhere, allWhere, null);
    }

    public static Rule update(String label, String table, String set, String playerWhere, String allWhere) {
        return new Rule(label, table, playerWhere, allWhere, set);
    }

    public static Blocker blocker(String label, String table, String playerWhere) {
        return new Blocker(label, table, playerWhere);
    }

    @Override
    public String covers() {
        return covers;
    }

    @Override
    protected List<String> requiredTables() {
        return tables;
    }

    private static int marks(String sql) {
        int n = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                n++;
            }
        }
        return n;
    }

    @Override
    protected Plan plan(Connection c, UUID target, long now) throws SQLException {
        List<Change> changes = new ArrayList<>();
        for (Rule r : rules) {
            String where = target == null ? r.allWhere() : r.playerWhere();
            List<Object> params = target == null ? List.of() : params(target, marks(where));
            String set = r.set() == null ? null : r.set().replace("{now}", Long.toString(now));
            changes.add(new Change(r.label(), r.table(), where, params, set));
        }
        List<String> blocked = new ArrayList<>();
        for (Blocker b : blockers) {
            if (target == null) {
                long n = Sqlite.count(c, "SELECT COUNT(*) FROM " + b.table() + " WHERE " + allOf(b.playerWhere()));
                if (n > 0) {
                    blocked.add(n + " " + b.label());
                }
                continue;
            }
            long n = Sqlite.count(c, "SELECT COUNT(*) FROM " + b.table() + " WHERE " + b.playerWhere(),
                    params(target, marks(b.playerWhere())).toArray());
            if (n > 0) {
                blocked.add(n + " " + b.label() + " (finish or resolve them in " + id + " first, then /wipe retry)");
            }
        }
        return new Plan(changes, blocked, List.of());
    }

    /** Blocker conditions are written for one player; for the season-reset preview drop the player conditions. */
    private static String allOf(String playerWhere) {
        // every blocker is written as "<state condition> AND (<player condition>)"
        int and = playerWhere.indexOf(" AND ");
        return and > 0 ? playerWhere.substring(0, and) : "1=1";
    }

    @Override
    protected void describe(Connection c, UUID target, Preview preview) throws SQLException {
        if (placed == null || target == null) {
            return;
        }
        List<String> where = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(placed.sql())) {
            Sqlite.bind(ps, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && where.size() < 8) {
                    where.add(rs.getString(1) + " " + rs.getInt(2) + " " + rs.getInt(3) + " " + rs.getInt(4));
                }
            }
        }
        if (!where.isEmpty()) {
            preview.warn(where.size() + " placed " + placed.label() + " stay in the world as inactive blocks: " + String.join("; ", where)
                    + " (cleared with the island if they are on the target's island)");
        }
    }
}
