package dev.exo.cleanwipe.storage;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only audit trail: one JSON object per line in {@code plugins/ExoCleanWipe/audit.log}, mirrored into the
 * {@code audit} table. Records who did what, to which UUIDs, why, when, which integrations ran and the result.
 * Never contains API keys.
 */
public final class AuditLog {

    private final Path file;
    private final CoreStore store;
    private final Logger logger;
    private final Gson gson = new Gson();

    public AuditLog(Path file, CoreStore store, Logger logger) {
        this.file = file;
        this.store = store;
        this.logger = logger;
    }

    public synchronized void record(String actor, String action, String targets, String reason, String result, String detail) {
        long now = System.currentTimeMillis();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", Instant.ofEpochMilli(now).toString());
        line.put("actor", actor);
        line.put("action", action);
        line.put("targets", targets);
        line.put("reason", reason);
        line.put("result", result);
        line.put("detail", detail);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(line) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "could not write audit.log", e);
        }
        try {
            store.audit(actor, action, targets, reason, result, detail, now);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "could not write audit table", e);
        }
    }
}
