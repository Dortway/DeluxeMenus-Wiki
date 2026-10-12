package dev.exo.cleanwipe.integrations.files;

import dev.exo.cleanwipe.wipe.Backups;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StartupIntegration;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ExoVaults 1.0.0 keeps all five vaults of a player in {@code data/<uuid>.dat} (plus {@code .dat.bak},
 * {@code .dat.tmp} and quarantined {@code .corrupt-*} copies). The record also contains an inventory checkpoint that
 * ExoVaults restores on join when its markers disagree, so every one of these files must go.
 *
 * <p>This runs at startup only: while the server runs, ExoVaults remembers the last written revision per player in
 * memory and refuses older revisions, so deleting a record live would make every later save of that player fail.
 * Files are moved (not copied) into the job backup, which is also how they are restored.
 */
public final class ExoVaultsIntegration implements StartupIntegration {

    private final Path dataDir;

    public ExoVaultsIntegration(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public String id() {
        return "ExoVaults";
    }

    @Override
    public String covers() {
        return "all player vaults (/pv 1-5) and their stored inventory checkpoint";
    }

    @Override
    public Availability availability() {
        return Files.isDirectory(dataDir) ? Availability.ok() : Availability.missing("no vault folder at " + dataDir);
    }

    List<Path> filesOf(UUID target) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dataDir)) {
            return out;
        }
        String prefix = target == null ? null : target + ".dat";
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dataDir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                if (prefix == null ? name.matches("[0-9a-fA-F-]{36}\\.dat.*") : name.startsWith(prefix)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    @Override
    public Preview preview(UUID target) throws Exception {
        Preview p = new Preview();
        List<Path> files = filesOf(target);
        if (!files.isEmpty()) {
            long bytes = 0;
            for (Path f : files) {
                bytes += Files.size(f);
            }
            p.line("vault record files", files.size() + " (" + bytes / 1024 + " kb)");
        }
        return p;
    }

    @Override
    public Preview previewAll() throws Exception {
        return preview(null);
    }

    @Override
    public StepResult wipe(StepContext ctx, UUID target) throws Exception {
        return move(ctx, filesOf(target));
    }

    @Override
    public StepResult wipeAll(StepContext ctx) throws Exception {
        return move(ctx, filesOf(null));
    }

    private StepResult move(StepContext ctx, List<Path> files) throws IOException {
        if (files.isEmpty()) {
            return StepResult.nothing("no vault records");
        }
        Path dir = ctx.backupDirCreated();
        for (Path f : files) {
            Path dest = dir.resolve(f.getFileName());
            if (Files.exists(dest)) {
                // an earlier attempt already saved the original; this one is newer, keep it beside it
                dest = dir.resolve(f.getFileName() + ".later-" + ctx.now());
            }
            Backups.move(f, dest);
        }
        return StepResult.done("moved " + files.size() + " vault file(s) into the backup");
    }

    @Override
    public StepResult restore(StepContext ctx, Path source) throws Exception {
        if (!Files.isDirectory(source)) {
            return StepResult.nothing("no vault backup");
        }
        Files.createDirectories(dataDir);
        Path replaced = ctx.backupDirCreated();
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(source, "*.dat*")) {
            for (Path f : ds) {
                String name = f.getFileName().toString();
                if (name.contains(".later-") || name.endsWith(".tmp")) {
                    continue;
                }
                Path dest = dataDir.resolve(name);
                if (Files.exists(dest)) {
                    Backups.copyOnce(dest, replaced.resolve(name + ".replaced"));
                }
                Files.copy(f, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        }
        return n == 0 ? StepResult.nothing("no vault files in backup") : StepResult.done("restored " + n + " vault file(s)");
    }
}
