package dev.exo.dailyspinner.spin;

import dev.exo.dailyspinner.storage.ReconcileEntry;
import dev.exo.dailyspinner.storage.SpinRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records deliveries that administrators may need to check (crash mid-delivery, failed commands,
 * unreadable items). Lines go to the server log and to {@code reconciliation.log}; the file is
 * written on the plugin's IO thread.
 */
public final class ReconciliationLog {

    private final Logger logger;
    private final Path file;
    private final ExecutorService io;

    public ReconciliationLog(Logger logger, Path file, ExecutorService io) {
        this.logger = logger;
        this.file = file;
        this.io = io;
    }

    public void record(String event, SpinRecord spin, String detail) {
        write(event + " spin=" + spin.id() + " player=" + spin.player() + " reward=" + spin.snapshot().rewardId()
                + " type=" + spin.snapshot().type() + " :: " + detail);
    }

    public void record(String event, UUID player, String ref, String detail) {
        write(event + " ref=" + ref + " player=" + player + " :: " + detail);
    }

    public void record(String event, ReconcileEntry entry) {
        write(event + " key=" + entry.key() + " player=" + entry.player() + " reward=" + entry.rewardId()
                + " kind=" + entry.kind() + " status=" + entry.status() + " :: " + entry.detail());
    }

    private void write(String line) {
        String stamped = Instant.now() + " " + line;
        logger.warning("[Reconcile] " + line);
        try {
            io.execute(() -> {
                try {
                    Files.writeString(file, stamped + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not write reconciliation.log", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutting down; the server log line above is the record.
        }
    }
}
