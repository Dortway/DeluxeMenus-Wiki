package dev.exodaily.core.audit;

import dev.exodaily.core.storage.AuditEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Human-readable audit trail, written in addition to the {@code audit_log} table. Must be used
 * from the storage thread.
 */
public final class AuditLog {

    private final Path file;
    private final Logger logger;
    private volatile boolean enabled;

    public AuditLog(Path file, boolean enabled, Logger logger) {
        this.file = file;
        this.enabled = enabled;
        this.logger = logger;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void append(AuditEntry entry) {
        if (!enabled) {
            return;
        }
        String line = Instant.ofEpochMilli(entry.at()) + " | " + entry.actor() + " | " + entry.action() + " | "
                + entry.target() + " | " + entry.details().replace('\n', ' ') + System.lineSeparator();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write to audit log " + file, e);
        }
    }
}
