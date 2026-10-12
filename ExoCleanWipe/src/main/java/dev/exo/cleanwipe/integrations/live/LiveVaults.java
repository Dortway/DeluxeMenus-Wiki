package dev.exo.cleanwipe.integrations.live;

import dev.exo.cleanwipe.integrations.files.ExoVaultsIntegration;
import dev.exo.cleanwipe.wipe.Backups;
import dev.exo.cleanwipe.wipe.LiveIntegration;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import java.lang.reflect.Constructor;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * ExoVaults 1.0.0 without a restart. ExoVaults refuses any record whose revision is not newer than the last one it
 * wrote or loaded, so deleting the file live would make every later save of that player fail. Instead an empty
 * record is written through ExoVaults' own {@code RecordRepository.write} with the next revision: all five vaults and
 * the stored inventory checkpoint are empty, and the revision chain stays valid. The previous record (and its
 * {@code .bak} / corrupt copies, which still hold the items) are kept in the job backup and removed.
 */
public final class LiveVaults implements LiveIntegration {

    private static final String RECORD = "dev.exo.vaults.storage.PlayerRecord";

    private final ExoVaultsIntegration files;
    private final Path dataDir;
    private final Executor io;

    public LiveVaults(ExoVaultsIntegration files, Path dataDir, Executor io) {
        this.files = files;
        this.dataDir = dataDir;
        this.io = io;
    }

    @Override
    public String id() {
        return files.id();
    }

    @Override
    public String covers() {
        return files.covers() + " (live)";
    }

    @Override
    public Availability availability() {
        return files.availability();
    }

    @Override
    public Preview preview(UUID target) throws Exception {
        Preview p = files.preview(target);
        return p;
    }

    @Override
    public Preview previewAll() throws Exception {
        return files.previewAll();
    }

    private static Plugin running() {
        Plugin p = Bukkit.getPluginManager().getPlugin("ExoVaults");
        return p != null && p.isEnabled() ? p : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<StepResult> wipe(StepContext ctx, UUID target) {
        Plugin owner = running();
        if (owner == null) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return files.wipe(ctx, target);
                } catch (Exception e) {
                    return StepResult.failed(e);
                }
            }, io);
        }
        Object repository;
        try {
            Object service = Reflect.call(owner, "vaults");
            if (Reflect.call(service, "data", target) != null) {
                return CompletableFuture.completedFuture(StepResult.deferred("ExoVaults still has the player's vaults loaded"));
            }
            Object saveQueue = Reflect.field(service, "saveQueue");
            if (Boolean.TRUE.equals(Reflect.call(saveQueue, "hasPending", target))) {
                return CompletableFuture.completedFuture(StepResult.deferred("ExoVaults has an unsaved vault write for the player"));
            }
            ((Map<UUID, ?>) Reflect.field(service, "preloaded")).remove(target);
            repository = Reflect.field(service, "repository");
        } catch (Exception | LinkageError e) {
            return CompletableFuture.completedFuture(StepResult.deferred("could not reach ExoVaults (" + e.getMessage() + ")"));
        }
        ClassLoader loader = owner.getClass().getClassLoader();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return writeEmpty(ctx, target, repository, loader);
            } catch (Exception e) {
                return StepResult.deferred("ExoVaults live write failed (" + e.getMessage() + ")");
            }
        }, io);
    }

    private StepResult writeEmpty(StepContext ctx, UUID target, Object repository, ClassLoader loader) throws Exception {
        List<Path> existing = filesOf(target);
        if (existing.isEmpty()) {
            return StepResult.nothing("no vault records");
        }
        Path dir = ctx.backupDirCreated();
        for (Path f : existing) {
            Backups.copyOnce(f, dir.resolve(f.getFileName()));
        }
        long onDisk = -1;
        Object loaded = Reflect.call(repository, "load", target);
        if (loaded.getClass().getSimpleName().equals("Found")) {
            Object record = Reflect.call(loaded, "record");
            onDisk = (Long) Reflect.call(record, "revision");
            if (((Map<?, ?>) Reflect.call(record, "vaults")).isEmpty() && ((List<?>) Reflect.call(record, "inventory")).isEmpty()) {
                removeOldCopies(target);
                return StepResult.done("vaults already empty (revision " + onDisk + ")");
            }
        } else if (loaded.getClass().getSimpleName().equals("Failed")) {
            return StepResult.deferred("ExoVaults cannot read the player's record (" + Reflect.call(loaded, "reason") + ")");
        }
        long known = (Long) Reflect.call(repository, "lastWrittenRevision", target);
        long revision = Math.max(onDisk, known) + 1;
        Class<?> recordClass = Class.forName(RECORD, true, loader);
        Constructor<?> ctor = recordClass.getConstructor(UUID.class, long.class, long.class, SortedMap.class, List.class, boolean.class);
        Object empty = ctor.newInstance(target, revision, ctx.now(), new TreeMap<>(), List.of(), false);
        Object written = Reflect.call(repository, "write", empty);
        if (!Boolean.TRUE.equals(written)) {
            return StepResult.deferred("ExoVaults refused the empty record (a newer revision exists)");
        }
        removeOldCopies(target);
        Object check = Reflect.call(repository, "load", target);
        if (!check.getClass().getSimpleName().equals("Found")
                || !((Map<?, ?>) Reflect.call(Reflect.call(check, "record"), "vaults")).isEmpty()) {
            return StepResult.deferred("could not confirm the empty vault record");
        }
        return StepResult.done("all vaults emptied (empty record, revision " + revision + "); previous record in the backup");
    }

    /** The backup copy and quarantined copies still hold the old items; ExoVaults falls back to them when the main file breaks. */
    private void removeOldCopies(UUID target) throws Exception {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dataDir, target + ".dat*")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (!n.equals(target + ".dat")) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private List<Path> filesOf(UUID target) throws Exception {
        List<Path> out = new java.util.ArrayList<>();
        if (!Files.isDirectory(dataDir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dataDir, target + ".dat*")) {
            ds.forEach(out::add);
        }
        return out;
    }

    @Override
    public CompletableFuture<StepResult> wipeAll(StepContext ctx) {
        if (running() == null) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return files.wipeAll(ctx);
                } catch (Exception e) {
                    return StepResult.failed(e);
                }
            }, io);
        }
        return CompletableFuture.completedFuture(StepResult.deferred("the season reset of ExoVaults runs while it is stopped"));
    }

    @Override
    public CompletableFuture<StepResult> restore(StepContext ctx, UUID target, Path source) {
        if (running() == null) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return files.restore(ctx, source);
                } catch (Exception e) {
                    return StepResult.failed(e);
                }
            }, io);
        }
        return CompletableFuture.completedFuture(StepResult.deferred("vault restores run while ExoVaults is stopped"));
    }
}
