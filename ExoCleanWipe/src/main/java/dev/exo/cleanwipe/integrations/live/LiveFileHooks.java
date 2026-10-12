package dev.exo.cleanwipe.integrations.live;

import dev.exo.cleanwipe.wipe.Backups;
import dev.exo.cleanwipe.wipe.LiveIntegration;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StartupIntegration;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Live versions of the YAML-file integrations. Both plugins keep the data in memory and rewrite their file from
 * memory, so the in-memory entry is removed through the plugin (EGen's own {@code ChestStorageManager.remove}) and the
 * plugin then saves. Backups are taken from memory (the file can lag behind).
 */
public final class LiveFileHooks implements LiveIntegration {

    private interface Work {
        StepResult run(Plugin owner, StepContext ctx, UUID target) throws Exception;
    }

    private final StartupIntegration files;
    private final String pluginName;
    private final Work work;
    private final Executor io;

    private LiveFileHooks(StartupIntegration files, String pluginName, Work work, Executor io) {
        this.files = files;
        this.pluginName = pluginName;
        this.work = work;
        this.io = io;
    }

    /** EGen 1.5.0: owned gen chests emptied with ChestStorageManager.remove(id), purchased limit removed. */
    public static LiveFileHooks egen(StartupIntegration files, Executor io) {
        return new LiveFileHooks(files, "EGen", LiveFileHooks::egen, io);
    }

    /** ExoSpawners 2.1.0: the player's entry is removed from Progress.players, then Progress.saveAsync(). */
    public static LiveFileHooks spawners(StartupIntegration files, Executor io) {
        return new LiveFileHooks(files, "ExoSpawners", LiveFileHooks::spawners, io);
    }

    @SuppressWarnings("unchecked")
    private static StepResult egen(Plugin owner, StepContext ctx, UUID target) throws Exception {
        Object machines = Reflect.field(owner, "machineManager");
        Object chests = Reflect.field(owner, "chestStorageManager");
        Object limits = Reflect.field(owner, "placementLimitManager");
        Map<UUID, ItemStack[]> stored = (Map<UUID, ItemStack[]>) Reflect.field(chests, "stored");
        List<UUID> owned = new ArrayList<>();
        for (Object m : (List<?>) Reflect.call(machines, "getAll")) {
            if (target.equals(Reflect.call(m, "getOwner"))) {
                owned.add((UUID) Reflect.call(m, "getId"));
            }
        }
        Path dir = ctx.backupDirCreated();
        int emptied = 0;
        for (UUID id : owned) {
            if (Boolean.TRUE.equals(Reflect.call(chests, "hasViewers", id))) {
                return StepResult.deferred("someone has one of the player's gen chests open");
            }
            Reflect.call(chests, "snapshot", id);
            ItemStack[] items = stored.get(id);
            if (items == null || Boolean.TRUE.equals(Reflect.call(chests, "isEmpty", id))) {
                continue;
            }
            Backups.writeOnce(dir.resolve("chest-" + id + ".items"), ItemStack.serializeItemsAsBytes(items));
            Reflect.call(chests, "remove", id);
            emptied++;
        }
        Map<UUID, Integer> purchased = (Map<UUID, Integer>) Reflect.field(limits, "purchasedLimits");
        Integer limit = purchased.get(target);
        if (limit != null) {
            Backups.writeOnce(dir.resolve("limit.txt"), (target + ": " + limit).getBytes(StandardCharsets.UTF_8));
            purchased.remove(target);
            Reflect.call(limits, "save");
        }
        if (emptied == 0 && limit == null) {
            return StepResult.nothing("no gen chest items or limit");
        }
        for (UUID id : owned) {
            if (stored.containsKey(id)) {
                return StepResult.deferred("EGen kept the chest contents in memory");
            }
        }
        return StepResult.done("emptied " + emptied + " gen chest(s)" + (limit == null ? "" : ", limit " + limit + " reset"));
    }

    @SuppressWarnings("unchecked")
    private static StepResult spawners(Plugin owner, StepContext ctx, UUID target) throws Exception {
        Object progress = Reflect.field(owner, "progress");
        if (progress == null) {
            return StepResult.deferred("ExoSpawners progress is not loaded");
        }
        Map<UUID, ?> players = (Map<UUID, ?>) Reflect.field(progress, "players");
        if (!players.containsKey(target)) {
            return StepResult.nothing("no spawner progress");
        }
        Path dir = ctx.backupDirCreated();
        Backups.copyOnce(owner.getDataFolder().toPath().resolve("players.yml"), dir.resolve("players.yml.before"));
        Backups.writeOnce(dir.resolve("progress.txt"), (target + ": unlocked tiers " + Reflect.call(progress, "unlocked", target))
                .getBytes(StandardCharsets.UTF_8));
        synchronized (players) {
            players.remove(target);
        }
        Reflect.call(progress, "saveAsync");
        return StepResult.done("spawner progress removed");
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
        return files.preview(target);
    }

    @Override
    public Preview previewAll() throws Exception {
        return files.previewAll();
    }

    private Plugin running() {
        Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
        return p != null && p.isEnabled() ? p : null;
    }

    @Override
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
        try {
            // main thread: these plugins change their maps on the main thread
            return CompletableFuture.completedFuture(work.run(owner, ctx, target));
        } catch (Exception | LinkageError e) {
            return CompletableFuture.completedFuture(StepResult.deferred("could not update " + pluginName + "'s memory ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")"));
        }
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
        return CompletableFuture.completedFuture(StepResult.deferred("the season reset of " + pluginName + " runs while it is stopped"));
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
        return CompletableFuture.completedFuture(StepResult.deferred(pluginName + " restores run while it is stopped"));
    }
}
