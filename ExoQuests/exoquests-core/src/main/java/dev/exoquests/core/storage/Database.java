package dev.exoquests.core.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the single SQLite connection. Every operation runs on one dedicated thread, in submission
 * order, inside its own transaction (committed on success, rolled back on any exception). This gives
 * a total order over all writes, which the quest, points and purchase logic rely on.
 *
 * <p>Never call {@link CompletableFuture#join()} on a returned future from the server main thread
 * except during startup or shutdown.</p>
 */
public final class Database implements AutoCloseable {

    private final ExecutorService executor;
    private final Logger logger;
    private Connection connection;
    private volatile boolean closed;

    private Database(Logger logger) {
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ExoQuests-Database");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Opens (creating if needed) the database file and applies pending migrations. Blocks until done.
     */
    public static Database open(Path file, String synchronous, int busyTimeoutMillis, Logger logger)
            throws SQLException {
        Database db = new Database(logger);
        try {
            db.executor.submit(() -> {
                db.connect(file, synchronous, busyTimeoutMillis);
                Migrations.apply(db.connection, logger);
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            db.close();
            throw new SQLException("interrupted while opening database", e);
        } catch (java.util.concurrent.ExecutionException e) {
            db.close();
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException("failed to open database: " + cause, cause);
        }
        return db;
    }

    private void connect(Path file, String synchronous, int busyTimeoutMillis) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver (org.xerial:sqlite-jdbc) is not available", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=" + synchronous);
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
        }
        connection.setAutoCommit(false);
    }

    /** Runs {@code work} in a transaction on the database thread. */
    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("database is closed"));
            return future;
        }
        try {
            executor.execute(() -> runNow(work, future));
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new IllegalStateException("database is closed", e));
        }
        return future;
    }

    private <T> void runNow(SqlWork<T> work, CompletableFuture<T> future) {
        try {
            T result = work.run(connection);
            connection.commit();
            future.complete(result);
        } catch (Throwable t) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                logger.log(Level.SEVERE, "rollback failed", rollback);
            }
            future.completeExceptionally(t);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** Drains queued work, then closes the connection. Blocks up to 30 seconds. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.execute(() -> {
            try {
                if (connection != null) {
                    try (Statement st = connection.createStatement()) {
                        connection.commit();
                        connection.setAutoCommit(true);
                        st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    } catch (SQLException e) {
                        logger.log(Level.WARNING, "checkpoint on close failed", e);
                    }
                    connection.close();
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "closing database failed", e);
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.severe("database work did not finish within 30 seconds; forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
