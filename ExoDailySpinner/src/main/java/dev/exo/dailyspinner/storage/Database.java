package dev.exo.dailyspinner.storage;

import java.nio.file.Files;
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
 * Owns the SQLite connection and a single dedicated worker thread. All database work is queued on
 * that thread, so it never blocks the server thread and writes are naturally serialised.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface Work<T> {
        T run(SpinRepository repository) throws Exception;
    }

    private final Logger logger;
    private final ExecutorService executor;
    private final Connection connection;
    private final SpinRepository repository;
    private volatile boolean closed;

    private Database(Logger logger, Connection connection) {
        this.logger = logger;
        this.connection = connection;
        this.repository = new SpinRepository(connection);
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ExoDailySpinner-DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Opens (creating if needed) the database file and applies pending migrations. */
    public static Database open(Path file, int busyTimeoutMillis, Logger logger) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (Exception e) {
            throw new SQLException("Cannot create database directory for " + file, e);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found. ExoDailySpinner requires a Paper server, which bundles it.", e);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            configure(connection, busyTimeoutMillis);
            int version = Migrations.migrate(connection);
            logger.info("Database ready (schema v" + version + ").");
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // closing after failure
            }
            throw e;
        }
        return new Database(logger, connection);
    }

    public static void configure(Connection connection, int busyTimeoutMillis) throws SQLException {
        connection.setAutoCommit(true);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = " + Math.max(0, busyTimeoutMillis));
            st.execute("PRAGMA journal_mode = WAL");
            // FULL keeps committed transactions durable across power loss as well as crashes.
            st.execute("PRAGMA synchronous = FULL");
            st.execute("PRAGMA foreign_keys = ON");
        }
    }

    /** Queues work on the database thread. */
    public <T> CompletableFuture<T> submit(Work<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("database is closed"));
            return future;
        }
        try {
            executor.execute(() -> {
                try {
                    future.complete(work.run(repository));
                } catch (Throwable t) {
                    logger.log(Level.SEVERE, "Database operation failed", t);
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new IllegalStateException("database is closed", e));
        }
        return future;
    }

    /** Waits for queued work to finish (up to {@code timeoutSeconds}) and closes the connection. */
    @Override
    public void close() {
        closeWithin(15);
    }

    public void closeWithin(int timeoutSeconds) {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warning("Database queue did not drain within " + timeoutSeconds + "s; forcing shutdown.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        try {
            connection.close();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to close database connection", e);
        }
    }
}
