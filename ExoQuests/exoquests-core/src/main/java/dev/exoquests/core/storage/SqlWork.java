package dev.exoquests.core.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Unit of database work executed on the database thread inside a transaction. */
@FunctionalInterface
public interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
}
