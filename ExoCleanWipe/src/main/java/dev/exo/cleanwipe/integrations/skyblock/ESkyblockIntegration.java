package dev.exo.cleanwipe.integrations.skyblock;

import dev.exo.cleanwipe.storage.Sqlite;
import dev.exo.cleanwipe.wipe.Backups;
import dev.exo.cleanwipe.wipe.LiveIntegration;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import dev.exoskyblock.ExoSkyblockPlugin;
import dev.exoskyblock.model.Island;
import dev.exoskyblock.model.IslandRank;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/**
 * ESkyblock 2.0.0, live, through the plugin's own services (it keeps every island in memory, so its database must
 * not be edited while it runs). An owned island is deleted with {@code IslandLifecycle#delete} exactly as its
 * {@code /is admin disband} does (raids cleared, invites cleared, region cleared, rows removed). That removes the
 * island blocks, bank (money, shards, experience), value, upgrades and the shared island chest. Membership and co-op
 * on other islands is removed with {@code IslandManager#purgeMembership}.
 */
public final class ESkyblockIntegration implements LiveIntegration {

    private final SharedIslandPolicy policy;
    private final boolean backupRegions;

    public ESkyblockIntegration(SharedIslandPolicy policy, boolean backupRegions) {
        this.policy = policy;
        this.backupRegions = backupRegions;
    }

    @Override
    public String id() {
        return "ESkyblock";
    }

    @Override
    public String covers() {
        return "owned island (blocks, bank money/shards/exp, value, upgrades, island chest), memberships and co-ops";
    }

    private ExoSkyblockPlugin sb() {
        Plugin p = Bukkit.getPluginManager().getPlugin("ESkyblock");
        return p instanceof ExoSkyblockPlugin s && p.isEnabled() ? s : null;
    }

    @Override
    public Availability availability() {
        return sb() == null ? Availability.missing("ESkyblock is not enabled") : Availability.ok();
    }

    /** Island owned by the target, or null. */
    private Island owned(ExoSkyblockPlugin sb, UUID target) {
        for (Island i : sb.islands().all()) {
            if (i.isOwner(target)) {
                return i;
            }
        }
        return null;
    }

    private static String nameOf(UUID uuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() == null ? uuid.toString() : op.getName();
    }

    /** Must be called on the main thread (island objects are not thread safe). */
    private Preview previewSync(UUID target) {
        Preview p = new Preview();
        ExoSkyblockPlugin sb = sb();
        if (sb == null) {
            return p;
        }
        Island own = owned(sb, target);
        if (own != null) {
            p.line("owned island", own.name() + " (value " + own.value() + ", bank " + own.money().toPlainString() + " money, "
                    + own.shards().toPlainString() + " shards, " + own.exp() + " exp, upgrades " + own.upgrades() + ")");
            boolean shared = false;
            for (Map.Entry<UUID, IslandRank> m : own.members().entrySet()) {
                if (!m.getKey().equals(target)) {
                    shared = true;
                    p.shared(m.getKey(), nameOf(m.getKey()), "member (" + m.getValue().name().toLowerCase() + ") of island " + own.name());
                }
            }
            for (UUID coop : own.coops()) {
                p.shared(coop, nameOf(coop), "co-op on island " + own.name());
            }
            if (shared) {
                switch (policy) {
                    case REQUIRE_FLAG -> p.requireSharedDecision().warn("island " + own.name()
                            + " is shared: add --shared to disband it for everyone listed, or --keep-shared to keep it");
                    case DISBAND -> p.warn("island " + own.name() + " is shared and will be disbanded for every member (config)");
                    case KEEP -> p.warn("island " + own.name() + " is shared and will be kept (config); its bank and chest stay");
                }
            }
        }
        for (Island other : sb.islands().all()) {
            if (other == own) {
                continue;
            }
            if (other.isMember(target)) {
                p.line("member of island", other.name() + " (owner " + other.ownerName() + "; membership removed, island kept)");
            } else if (other.isCoop(target)) {
                p.line("co-op on island", other.name() + " (co-op removed, island kept)");
            }
        }
        return p;
    }

    @Override
    public Preview preview(UUID target) throws Exception {
        ExoSkyblockPlugin sb = sb();
        if (sb == null) {
            return Preview.empty();
        }
        if (Bukkit.isPrimaryThread()) {
            return previewSync(target);
        }
        CompletableFuture<Preview> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(sb, () -> {
            try {
                f.complete(previewSync(target));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get();
    }

    @Override
    public Preview previewAll() throws Exception {
        ExoSkyblockPlugin sb = sb();
        return sb == null ? Preview.empty() : Preview.empty().line("islands", sb.islands().count() + " (all disbanded, regions cleared)");
    }

    /** Copies islands.db with VACUUM INTO (consistent while ESkyblock has it open) and the island's region files. */
    private void backup(ExoSkyblockPlugin sb, Island island, Path dir) throws Exception {
        Files.createDirectories(dir);
        Path db = sb.getDataFolder().toPath().resolve("islands.db");
        Path copy = dir.resolve("islands.db.before");
        if (Files.exists(db) && !Files.exists(copy)) {
            try (Connection c = Sqlite.open(db, true); Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + copy.toAbsolutePath().toString().replace("'", "''") + "'");
            }
        }
        if (!backupRegions || island == null) {
            return;
        }
        World w = sb.islands().world();
        if (w == null) {
            return;
        }
        w.save();
        Path regionDir = regionFolder(w);
        if (regionDir == null) {
            return;
        }
        BoundingBox b = sb.islands().maxBounds(island);
        for (int rx = Math.floorDiv((int) b.getMinX(), 512); rx <= Math.floorDiv((int) b.getMaxX(), 512); rx++) {
            for (int rz = Math.floorDiv((int) b.getMinZ(), 512); rz <= Math.floorDiv((int) b.getMaxZ(), 512); rz++) {
                for (String sub : List.of("region", "entities", "poi")) {
                    Path f = regionDir.resolveSibling(sub).resolve("r." + rx + "." + rz + ".mca");
                    Backups.copyOnce(f, dir.resolve(sub + "-r." + rx + "." + rz + ".mca"));
                }
            }
        }
    }

    private static Path regionFolder(World w) {
        Path direct = w.getWorldFolder().toPath().resolve("region");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        // Minecraft 26.1 keeps extra levels under <level>/dimensions/<namespace>/<key>/
        Path level = Bukkit.getWorlds().get(0).getWorldFolder().toPath();
        Path dim = level.resolve("dimensions").resolve(w.getKey().namespace()).resolve(w.getKey().value()).resolve("region");
        return Files.isDirectory(dim) ? dim : null;
    }

    @Override
    public CompletableFuture<StepResult> wipe(StepContext ctx, UUID target) {
        ExoSkyblockPlugin sb = sb();
        if (sb == null) {
            return CompletableFuture.completedFuture(StepResult.failed("ESkyblock is not enabled"));
        }
        try {
            Island own = owned(sb, target);
            List<String> done = new ArrayList<>();
            backup(sb, own, ctx.backupDirCreated());
            List<Island> changed = sb.islands().purgeMembership(target, own);
            if (!changed.isEmpty()) {
                done.add("left " + changed.size() + " other island(s)");
            }
            if (own == null) {
                return CompletableFuture.completedFuture(done.isEmpty() ? StepResult.nothing("no island") : StepResult.done(String.join(", ", done)));
            }
            boolean shared = own.members().keySet().stream().anyMatch(u -> !u.equals(target)) || !own.coops().isEmpty();
            if (shared) {
                boolean disband = policy == SharedIslandPolicy.DISBAND || (policy == SharedIslandPolicy.REQUIRE_FLAG && ctx.options().disbandSharedIslands());
                boolean keep = policy == SharedIslandPolicy.KEEP || (policy == SharedIslandPolicy.REQUIRE_FLAG && ctx.options().keepSharedIslands());
                if (keep && !disband) {
                    done.add("kept shared island " + own.name());
                    return CompletableFuture.completedFuture(StepResult.skipped(String.join(", ", done)));
                }
                if (!disband) {
                    return CompletableFuture.completedFuture(StepResult.blocked("island " + own.name()
                            + " is shared; run the wipe again with --shared or --keep-shared"));
                }
            }
            return delete(sb, own, ctx).thenApply(r -> {
                if (r.state() != dev.exo.cleanwipe.storage.Records.StepState.DONE) {
                    return r;
                }
                done.add(r.detail());
                return StepResult.done(String.join(", ", done));
            });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(StepResult.failed(e));
        }
    }

    private CompletableFuture<StepResult> delete(ExoSkyblockPlugin sb, Island island, StepContext ctx) {
        CompletableFuture<StepResult> f = new CompletableFuture<>();
        String name = island.name();
        sb.raids().clearRaids(island);
        sb.members().clearInvitesFor(island);
        sb.admin().log(Bukkit.getConsoleSender(), "exocleanwipe job " + ctx.jobId() + " disbanded island " + name + " (" + island.id() + ")");
        sb.lifecycle().delete(island, () -> f.complete(StepResult.done("disbanded island " + name)));
        return f;
    }

    @Override
    public CompletableFuture<StepResult> wipeAll(StepContext ctx) {
        ExoSkyblockPlugin sb = sb();
        if (sb == null) {
            return CompletableFuture.completedFuture(StepResult.failed("ESkyblock is not enabled"));
        }
        try {
            backup(sb, null, ctx.backupDirCreated());
        } catch (Exception e) {
            return CompletableFuture.completedFuture(StepResult.failed(e));
        }
        Deque<Island> queue = new ArrayDeque<>(sb.islands().all());
        int total = queue.size();
        CompletableFuture<StepResult> result = new CompletableFuture<>();
        next(sb, queue, ctx, total, result);
        return result;
    }

    /** One island at a time: each region clear finishes before the next starts. */
    private void next(ExoSkyblockPlugin sb, Deque<Island> queue, StepContext ctx, int total, CompletableFuture<StepResult> result) {
        Island island = queue.poll();
        if (island == null) {
            result.complete(total == 0 ? StepResult.nothing("no islands") : StepResult.done("disbanded " + total + " island(s)"));
            return;
        }
        delete(sb, island, ctx).whenComplete((r, err) -> Bukkit.getScheduler().runTaskLater(sb, () -> next(sb, queue, ctx, total, result), 2L));
    }

    @Override
    public CompletableFuture<StepResult> restore(StepContext ctx, UUID target, Path source) {
        return CompletableFuture.completedFuture(Files.exists(source.resolve("islands.db.before"))
                ? StepResult.skipped("manual: islands cannot be restored live (blocks were cleared). see docs/RECOVERY.md, files in " + source)
                : StepResult.nothing("no island backup"));
    }

    public boolean bulk() {
        return true;
    }
}
