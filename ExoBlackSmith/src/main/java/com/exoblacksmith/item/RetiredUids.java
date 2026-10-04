package com.exoblacksmith.item;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Append-only record of unique item UIDs that were consumed by an upgrade. A duplicated copy of a
 * consumed mask therefore cannot be upgraded again and grants no effects. Appends are synchronous and
 * small (one line per upgrade) so a crash right after an upgrade cannot lose the record.
 */
public final class RetiredUids {
    private final Path file;
    private final Logger logger;
    private final Set<String> retired = new HashSet<>();

    public RetiredUids(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        retired.add(trimmed);
                    }
                }
            } catch (IOException e) {
                logger.severe("Could not read " + file + ": " + e.getMessage());
            }
        }
    }

    public boolean isRetired(String uid) {
        return uid != null && retired.contains(uid);
    }

    public void retire(String uid) {
        if (uid == null || !retired.add(uid)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, uid + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.severe("Could not persist retired uid " + uid + ": " + e.getMessage());
        }
    }

    public int size() {
        return retired.size();
    }
}
