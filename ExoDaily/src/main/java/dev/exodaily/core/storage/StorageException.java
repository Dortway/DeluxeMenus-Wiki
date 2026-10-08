package dev.exodaily.core.storage;

/** Persistence failure. Callers must fail closed: no reward is delivered when this is thrown. */
public final class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
