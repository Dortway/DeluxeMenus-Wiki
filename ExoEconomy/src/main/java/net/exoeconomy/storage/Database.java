package net.exoeconomy.storage;

import net.exoeconomy.api.BoosterScope;
import net.exoeconomy.core.booster.Booster;
import net.exoeconomy.core.booster.BoosterState;
import net.exoeconomy.core.reward.DailyLimits;
import net.exoeconomy.core.tx.Journal;
import net.exoeconomy.core.tx.TxKind;
import net.exoeconomy.core.tx.TxRecord;
import net.exoeconomy.core.tx.TxState;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ExoEconomy's own SQLite database. It never stores Shards balances: only boosters, transaction
 * and recovery records, escrowed sell items, block provenance and daily activity counters.
 *
 * <p>All statements run on one dedicated writer thread with a bounded queue, so the server
 * thread never blocks on disk. Startup loading is the exception: it runs once in onEnable,
 * before any player can interact. WAL journal and synchronous=FULL make every committed write
 * survive a crash or power loss.
 */
public final class Database implements Journal, AutoCloseable {
    public static final int SCHEMA_VERSION = 1;

    private final Connection db;
    private final ThreadPoolExecutor writer;
    private final Logger log;
    private volatile boolean healthy = true;
    private volatile String failure;

    public Database(Path file, int queueCapacity, Logger log) throws SQLException {
        this.log = log;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found (the server normally bundles it)", e);
        }
        this.db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA foreign_keys=ON");
        }
        migrate();
        this.writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                r -> { Thread t = new Thread(r, "ExoEconomy-storage"); t.setDaemon(false); return t; });
    }

    // ------------------------------------------------------------------ schema

    private void migrate() throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
        int version = Integer.parseInt(meta("schema_version").orElse("0"));
        if (version > SCHEMA_VERSION)
            throw new SQLException("Database schema " + version + " is newer than this ExoEconomy build supports (" + SCHEMA_VERSION + "). Install the newer plugin or restore a backup.");
        db.setAutoCommit(false);
        try (Statement s = db.createStatement()) {
            if (version < 1) {
                s.execute("""
                    CREATE TABLE transactions(
                      id TEXT PRIMARY KEY, player TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL,
                      amount TEXT NOT NULL, currency TEXT NOT NULL, parent TEXT, details TEXT NOT NULL,
                      balance_before TEXT, balance_after TEXT, created INTEGER NOT NULL, updated INTEGER NOT NULL, note TEXT)""");
                s.execute("CREATE INDEX tx_player ON transactions(player, created)");
                s.execute("CREATE INDEX tx_state ON transactions(state)");
                // At most one live refund/payout per parent; failed attempts free the slot.
                s.execute("CREATE UNIQUE INDEX tx_parent_kind ON transactions(parent, kind) WHERE parent IS NOT NULL AND state NOT IN ('FAILED','ABORTED','RESOLVED_FAILED')");
                s.execute("""
                    CREATE TABLE boosters(
                      id TEXT PRIMARY KEY, tier TEXT NOT NULL, scope TEXT NOT NULL, owner TEXT, activity TEXT NOT NULL,
                      multiplier TEXT NOT NULL, state TEXT NOT NULL, expires_at INTEGER NOT NULL, remaining_ms INTEGER NOT NULL,
                      sequence INTEGER NOT NULL, paused_by_server INTEGER NOT NULL, source_tx TEXT, created INTEGER NOT NULL)""");
                s.execute("CREATE INDEX boosters_state ON boosters(state)");
                s.execute("""
                    CREATE TABLE escrow(
                      session TEXT PRIMARY KEY, player TEXT NOT NULL, state TEXT NOT NULL, tx TEXT,
                      items BLOB NOT NULL, summary TEXT NOT NULL, updated INTEGER NOT NULL)""");
                s.execute("""
                    CREATE TABLE claims(
                      id TEXT PRIMARY KEY, player TEXT NOT NULL, state TEXT NOT NULL, tx TEXT, reason TEXT NOT NULL,
                      items BLOB NOT NULL, summary TEXT NOT NULL, created INTEGER NOT NULL)""");
                s.execute("CREATE INDEX claims_player ON claims(player, state)");
                s.execute("""
                    CREATE TABLE placed_blocks(
                      world TEXT NOT NULL, chunk INTEGER NOT NULL, pos INTEGER NOT NULL, kind INTEGER NOT NULL,
                      PRIMARY KEY(world, chunk, pos)) WITHOUT ROWID""");
                s.execute("""
                    CREATE TABLE activity_daily(
                      player TEXT NOT NULL, day INTEGER NOT NULL, activity TEXT NOT NULL, amount TEXT NOT NULL,
                      PRIMARY KEY(player, day, activity)) WITHOUT ROWID""");
                setMeta(s, "schema_version", "1");
            }
            // Future migrations: if (version < 2) { ...; setMeta(s, "schema_version", "2"); }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    private void setMeta(Statement s, String key, String value) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT OR REPLACE INTO meta VALUES(?,?)")) {
            p.setString(1, key);
            p.setString(2, value);
            p.executeUpdate();
        }
    }

    /** Synchronous; startup and shutdown only. */
    public Optional<String> meta(String key) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            p.setString(1, key);
            try (ResultSet r = p.executeQuery()) { return r.next() ? Optional.of(r.getString(1)) : Optional.empty(); }
        }
    }

    public static Work putMeta(String key, String value) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO meta VALUES(?,?)")) {
                p.setString(1, key);
                p.setString(2, value);
                p.executeUpdate();
            }
        };
    }

    // ------------------------------------------------------------------ execution

    @Override
    public boolean healthy() { return healthy; }

    public String failure() { return failure; }

    /** Runs {@code work} atomically on the writer thread. */
    public CompletableFuture<Void> write(List<Work> work) {
        return submit(c -> {
            for (Work w : work) w.run(c);
            return null;
        });
    }

    /** Runs a read or write on the writer thread inside a transaction and returns its result. */
    public <T> CompletableFuture<T> submit(Query<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                try {
                    db.setAutoCommit(false);
                    T result = query.run(db);
                    db.commit();
                    future.complete(result);
                } catch (Throwable t) {
                    try { db.rollback(); } catch (SQLException ignored) { }
                    if (!(t instanceof DuplicateTransactionException)) markFailed(t);
                    future.completeExceptionally(t);
                } finally {
                    try { db.setAutoCommit(true); } catch (SQLException ignored) { }
                }
            });
        } catch (RejectedExecutionException e) {
            log.warning("Storage queue is full; request refused");
            future.completeExceptionally(e);
        }
        return future;
    }

    private void markFailed(Throwable t) {
        if (healthy) log.log(Level.SEVERE, "Storage write failed; purchases, selling and refunds are suspended until storage recovers", t);
        healthy = false;
        failure = String.valueOf(t);
    }

    /** Tries a trivial write; restores health if it succeeds. */
    public CompletableFuture<Boolean> probe() {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                try (Statement s = db.createStatement()) {
                    s.execute("INSERT OR REPLACE INTO meta VALUES('probe', '" + System.currentTimeMillis() + "')");
                    healthy = true;
                    failure = null;
                    result.complete(true);
                } catch (Throwable t) {
                    result.complete(false);
                }
            });
        } catch (RejectedExecutionException e) {
            result.complete(false);
        }
        return result;
    }

    public int queued() { return writer.getQueue().size(); }

    @FunctionalInterface
    public interface Query<T> { T run(Connection c) throws Exception; }

    // ------------------------------------------------------------------ journal

    @Override
    public CompletableFuture<Void> begin(TxRecord r, List<Work> extras) {
        return submit(c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT INTO transactions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                p.setString(1, r.id());
                p.setString(2, r.player().toString());
                p.setString(3, r.kind().name());
                p.setString(4, r.state().name());
                p.setString(5, r.amount().toPlainString());
                p.setString(6, r.currency());
                p.setString(7, r.parent());
                p.setString(8, r.details());
                p.setString(9, null);
                p.setString(10, null);
                p.setLong(11, r.createdAt());
                p.setLong(12, r.updatedAt());
                p.setString(13, r.note());
                p.executeUpdate();
            } catch (SQLException e) {
                if (String.valueOf(e.getMessage()).contains("UNIQUE") && r.parent() != null)
                    throw new DuplicateTransactionException("A " + r.kind() + " for " + r.parent() + " already exists");
                throw e;
            }
            for (Work w : extras) w.run(c);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> finish(String id, TxState state, BigDecimal before, BigDecimal after, String note, List<Work> extras) {
        return submit(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE transactions SET state=?, balance_before=COALESCE(?, balance_before), balance_after=COALESCE(?, balance_after), updated=?, note=COALESCE(?, note) WHERE id=?")) {
                p.setString(1, state.name());
                p.setString(2, before == null ? null : before.toPlainString());
                p.setString(3, after == null ? null : after.toPlainString());
                p.setLong(4, System.currentTimeMillis());
                p.setString(5, note);
                p.setString(6, id);
                if (p.executeUpdate() != 1) throw new SQLException("Transaction " + id + " not found");
            }
            for (Work w : extras) w.run(c);
            return null;
        });
    }

    /** Admin resolution: only moves a record that is still in {@code expected}. */
    public static Work resolve(String id, TxState expected, TxState next, String note) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("UPDATE transactions SET state=?, updated=?, note=? WHERE id=? AND state=?")) {
                p.setString(1, next.name());
                p.setLong(2, System.currentTimeMillis());
                p.setString(3, note);
                p.setString(4, id);
                p.setString(5, expected.name());
                if (p.executeUpdate() != 1) throw new SQLException("Transaction " + id + " is no longer " + expected);
            }
        };
    }

    public CompletableFuture<Optional<TxRecord>> transaction(String id) {
        return submit(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM transactions WHERE id=? OR id LIKE ? ORDER BY created DESC LIMIT 2")) {
                p.setString(1, id);
                p.setString(2, id + "%");
                try (ResultSet r = p.executeQuery()) {
                    List<TxRecord> found = new ArrayList<>();
                    while (r.next()) found.add(tx(r));
                    return found.size() == 1 || (!found.isEmpty() && found.get(0).id().equals(id)) ? Optional.of(found.get(0)) : Optional.<TxRecord>empty();
                }
            }
        });
    }

    public CompletableFuture<List<TxRecord>> transactions(UUID player, Set<TxState> states, int limit) {
        return submit(c -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM transactions WHERE 1=1");
            if (player != null) sql.append(" AND player=?");
            if (states != null && !states.isEmpty()) sql.append(" AND state IN (").append("?,".repeat(states.size() - 1)).append("?)");
            sql.append(" ORDER BY created DESC LIMIT ?");
            try (PreparedStatement p = c.prepareStatement(sql.toString())) {
                int i = 1;
                if (player != null) p.setString(i++, player.toString());
                if (states != null) for (TxState s : states) p.setString(i++, s.name());
                p.setInt(i, limit);
                List<TxRecord> out = new ArrayList<>();
                try (ResultSet r = p.executeQuery()) { while (r.next()) out.add(tx(r)); }
                return out;
            }
        });
    }

    public CompletableFuture<List<TxRecord>> children(String parent) {
        return submit(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM transactions WHERE parent=? ORDER BY created")) {
                p.setString(1, parent);
                List<TxRecord> out = new ArrayList<>();
                try (ResultSet r = p.executeQuery()) { while (r.next()) out.add(tx(r)); }
                return out;
            }
        });
    }

    private static TxRecord tx(ResultSet r) throws SQLException {
        return new TxRecord(r.getString("id"), UUID.fromString(r.getString("player")), TxKind.valueOf(r.getString("kind")),
                TxState.valueOf(r.getString("state")), new BigDecimal(r.getString("amount")), r.getString("currency"),
                r.getString("parent"), r.getString("details"), decimal(r.getString("balance_before")),
                decimal(r.getString("balance_after")), r.getLong("created"), r.getLong("updated"), r.getString("note"));
    }

    private static BigDecimal decimal(String s) { return s == null ? null : new BigDecimal(s); }

    // ------------------------------------------------------------------ boosters

    public List<Booster> loadBoosters() throws SQLException {
        List<Booster> out = new ArrayList<>();
        try (Statement s = db.createStatement();
             ResultSet r = s.executeQuery("SELECT * FROM boosters WHERE state IN ('ACTIVE','QUEUED','PAUSED') ORDER BY sequence")) {
            while (r.next()) {
                String owner = r.getString("owner");
                out.add(new Booster(r.getString("id"), r.getString("tier"), BoosterScope.valueOf(r.getString("scope")),
                        owner == null ? null : UUID.fromString(owner), r.getString("activity"), new BigDecimal(r.getString("multiplier")),
                        r.getString("source_tx"), r.getLong("created"), BoosterState.valueOf(r.getString("state")),
                        r.getLong("expires_at"), r.getLong("remaining_ms"), r.getLong("sequence"), r.getInt("paused_by_server") != 0));
            }
        }
        return out;
    }

    public static Work saveBoosters(List<Booster> boosters) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO boosters VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (Booster b : boosters) {
                    p.setString(1, b.id);
                    p.setString(2, b.tierId);
                    p.setString(3, b.scope.name());
                    p.setString(4, b.owner == null ? null : b.owner.toString());
                    p.setString(5, b.activity);
                    p.setString(6, b.multiplier.toPlainString());
                    p.setString(7, b.state.name());
                    p.setLong(8, b.expiresAt);
                    p.setLong(9, b.remainingMs);
                    p.setLong(10, b.sequence);
                    p.setInt(11, b.pausedByServer ? 1 : 0);
                    p.setString(12, b.sourceTx);
                    p.setLong(13, b.createdAt);
                    p.addBatch();
                }
                p.executeBatch();
            }
        };
    }

    // ------------------------------------------------------------------ escrow & claims

    public record Escrow(String session, UUID player, String state, String tx, byte[] items, String summary, long updated) {}
    public record Claim(String id, UUID player, String state, String tx, String reason, byte[] items, String summary, long created) {}

    public static final String CLAIM_CLAIMABLE = "CLAIMABLE";
    public static final String CLAIM_REVIEW = "REVIEW";

    public static Work saveEscrow(String session, UUID player, String state, String tx, byte[] items, String summary) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO escrow VALUES(?,?,?,?,?,?,?)")) {
                p.setString(1, session);
                p.setString(2, player.toString());
                p.setString(3, state);
                p.setString(4, tx);
                p.setBytes(5, items);
                p.setString(6, summary);
                p.setLong(7, System.currentTimeMillis());
                p.executeUpdate();
            }
        };
    }

    public static Work deleteEscrow(String session) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM escrow WHERE session=?")) {
                p.setString(1, session);
                p.executeUpdate();
            }
        };
    }

    public static Work insertClaim(Claim claim) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT INTO claims VALUES(?,?,?,?,?,?,?,?)")) {
                p.setString(1, claim.id());
                p.setString(2, claim.player().toString());
                p.setString(3, claim.state());
                p.setString(4, claim.tx());
                p.setString(5, claim.reason());
                p.setBytes(6, claim.items());
                p.setString(7, claim.summary());
                p.setLong(8, claim.created());
                p.executeUpdate();
            }
        };
    }

    /** Deletes a claim only if it is still in {@code expectedState}; fails otherwise (prevents double delivery). */
    public static Work deleteClaim(String id, String expectedState) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM claims WHERE id=? AND state=?")) {
                p.setString(1, id);
                p.setString(2, expectedState);
                if (p.executeUpdate() != 1) throw new SQLException("Claim " + id + " is no longer " + expectedState);
            }
        };
    }

    public static Work setClaimState(String id, String expected, String next) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("UPDATE claims SET state=? WHERE id=? AND state=?")) {
                p.setString(1, next);
                p.setString(2, id);
                p.setString(3, expected);
                if (p.executeUpdate() != 1) throw new SQLException("Claim " + id + " is no longer " + expected);
            }
        };
    }

    public static Work setClaimStateByTx(String tx, String expected, String next) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("UPDATE claims SET state=? WHERE tx=? AND state=?")) {
                p.setString(1, next);
                p.setString(2, tx);
                p.setString(3, expected);
                p.executeUpdate();
            }
        };
    }

    public static Work deleteClaimsByTx(String tx, String expected) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM claims WHERE tx=? AND state=?")) {
                p.setString(1, tx);
                p.setString(2, expected);
                p.executeUpdate();
            }
        };
    }

    public CompletableFuture<List<Claim>> claims(UUID player, String state) {
        return submit(c -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM claims WHERE 1=1");
            if (player != null) sql.append(" AND player=?");
            if (state != null) sql.append(" AND state=?");
            sql.append(" ORDER BY created LIMIT 200");
            try (PreparedStatement p = c.prepareStatement(sql.toString())) {
                int i = 1;
                if (player != null) p.setString(i++, player.toString());
                if (state != null) p.setString(i, state);
                List<Claim> out = new ArrayList<>();
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) out.add(new Claim(r.getString("id"), UUID.fromString(r.getString("player")), r.getString("state"),
                            r.getString("tx"), r.getString("reason"), r.getBytes("items"), r.getString("summary"), r.getLong("created")));
                }
                return out;
            }
        });
    }

    // ------------------------------------------------------------------ recovery

    public record RecoveryReport(int uncertainTransactions, int recoveredEscrows, boolean cleanShutdown) {}

    /**
     * Startup recovery, synchronous, before any player interaction:
     * PENDING transactions become UNCERTAIN (never retried), and escrowed sell items left by a
     * crash become claims: REVIEW (admin approves) or CLAIMABLE (returned to the player) per config.
     */
    public RecoveryReport recover(boolean returnEscrowToPlayer) throws SQLException {
        boolean clean = "true".equals(meta("clean_shutdown").orElse("true"));
        db.setAutoCommit(false);
        try {
            int uncertain;
            try (PreparedStatement p = db.prepareStatement("UPDATE transactions SET state='UNCERTAIN', updated=?, note=COALESCE(note || '; ', '') || 'server stopped before completion' WHERE state='PENDING'")) {
                p.setLong(1, System.currentTimeMillis());
                uncertain = p.executeUpdate();
            }
            int escrows = 0;
            List<Escrow> leftovers = new ArrayList<>();
            try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM escrow")) {
                while (r.next()) leftovers.add(new Escrow(r.getString("session"), UUID.fromString(r.getString("player")),
                        r.getString("state"), r.getString("tx"), r.getBytes("items"), r.getString("summary"), r.getLong("updated")));
            }
            for (Escrow e : leftovers) {
                // A sale in progress is always held for review: whether it was paid is unknown.
                boolean selling = e.tx() != null && "SELLING".equals(e.state());
                String state = selling || !returnEscrowToPlayer ? CLAIM_REVIEW : CLAIM_CLAIMABLE;
                insertClaim(new Claim(UUID.randomUUID().toString(), e.player(), state, selling ? e.tx() : null,
                        selling ? "sale interrupted by shutdown" : "sell menu open during shutdown", e.items(), e.summary(),
                        System.currentTimeMillis())).run(db);
                deleteEscrow(e.session()).run(db);
                escrows++;
            }
            putMeta("clean_shutdown", "false").run(db);
            db.commit();
            return new RecoveryReport(uncertain, escrows, clean);
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    // ------------------------------------------------------------------ provenance

    public record PlacedRow(String world, long chunk, int pos, byte kind) {}

    /** Loads every provenance row; run on the writer thread via {@link #submit}. */
    public CompletableFuture<List<PlacedRow>> loadPlaced() {
        return submit(c -> {
            List<PlacedRow> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT world, chunk, pos, kind FROM placed_blocks")) {
                while (r.next()) out.add(new PlacedRow(r.getString(1), r.getLong(2), r.getInt(3), (byte) r.getInt(4)));
            }
            return out;
        });
    }

    /** kind 0 deletes the row. */
    public static Work writePlaced(List<PlacedRow> rows) {
        return c -> {
            try (PreparedStatement put = c.prepareStatement("INSERT OR REPLACE INTO placed_blocks VALUES(?,?,?,?)");
                 PreparedStatement del = c.prepareStatement("DELETE FROM placed_blocks WHERE world=? AND chunk=? AND pos=?")) {
                for (PlacedRow row : rows) {
                    PreparedStatement p = row.kind() == 0 ? del : put;
                    p.setString(1, row.world());
                    p.setLong(2, row.chunk());
                    p.setInt(3, row.pos());
                    if (row.kind() != 0) p.setInt(4, row.kind());
                    p.addBatch();
                }
                put.executeBatch();
                del.executeBatch();
            }
        };
    }

    // ------------------------------------------------------------------ daily counters

    public List<DailyLimits.Row> loadDaily(long day) throws SQLException {
        List<DailyLimits.Row> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT player, activity, amount FROM activity_daily WHERE day=?")) {
            p.setLong(1, day);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new DailyLimits.Row(UUID.fromString(r.getString(1)), day, r.getString(2), new BigDecimal(r.getString(3))));
            }
        }
        try (PreparedStatement p = db.prepareStatement("DELETE FROM activity_daily WHERE day < ?")) {
            p.setLong(1, day - 7);
            p.executeUpdate();
        }
        return out;
    }

    public static Work writeDaily(List<DailyLimits.Row> rows) {
        return c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO activity_daily VALUES(?,?,?,?)")) {
                for (DailyLimits.Row row : rows) {
                    p.setString(1, row.player().toString());
                    p.setLong(2, row.day());
                    p.setString(3, row.activity());
                    p.setString(4, row.amount().toPlainString());
                    p.addBatch();
                }
                p.executeBatch();
            }
        };
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Flushes queued writes, marks a clean shutdown and closes. Blocks up to 30 seconds; if the
     * queue does not drain, the database is left open so no queued write is cut off mid-way.
     */
    @Override
    public void close() throws Exception {
        CompletableFuture<Void> marker = healthy ? write(List.of(putMeta("clean_shutdown", "true"))) : CompletableFuture.completedFuture(null);
        writer.shutdown();
        if (!writer.awaitTermination(30, TimeUnit.SECONDS))
            throw new IllegalStateException("Storage still flushing after 30s; database left open");
        try { marker.getNow(null); } catch (CompletionException e) { log.warning("Could not mark clean shutdown: " + e.getCause()); }
        db.close();
    }

    public static Consumer<Throwable> logTo(Logger log, String what) {
        return t -> { if (t != null) log.log(Level.WARNING, what + " failed", t); };
    }
}
