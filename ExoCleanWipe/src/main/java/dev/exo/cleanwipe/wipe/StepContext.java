package dev.exo.cleanwipe.wipe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Everything a step needs. {@code backupDir} is unique per job, target and integration
 * ({@code backups/job-12/<uuid|all>/<integration>/}); a retried step reuses it and must never overwrite a backup
 * that an earlier attempt already wrote, so the backup always holds the data as it was before the first attempt.
 */
public record StepContext(long jobId, Path backupDir, Logger logger, long now, WipeOptions options) {

    public Path backupDirCreated() throws IOException {
        Files.createDirectories(backupDir);
        return backupDir;
    }
}
