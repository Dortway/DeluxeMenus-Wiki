package net.exoeconomy.core.tx;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Durable transaction journal. Writes run off the server thread; futures complete there too. */
public interface Journal {
    /** Extra statements executed in the same database transaction as a journal write. */
    @FunctionalInterface
    interface Work { void run(Connection connection) throws SQLException; }

    boolean healthy();

    /**
     * Inserts a PENDING record and runs {@code extras} atomically. Fails with
     * {@link DuplicateTransactionException} if the record's parent already has a child of the same kind.
     */
    CompletableFuture<Void> begin(TxRecord record, List<Work> extras);

    /** Moves a record to a final state and runs {@code extras} atomically. */
    CompletableFuture<Void> finish(String id, TxState state, BigDecimal before, BigDecimal after, String note, List<Work> extras);

    final class DuplicateTransactionException extends RuntimeException {
        public DuplicateTransactionException(String message) { super(message); }
    }
}
