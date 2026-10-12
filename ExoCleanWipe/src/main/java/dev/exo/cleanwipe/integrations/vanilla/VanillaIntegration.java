package dev.exo.cleanwipe.integrations.vanilla;

import dev.exo.cleanwipe.wipe.Backups;
import dev.exo.cleanwipe.wipe.LiveIntegration;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Vanilla player data: inventory, hotbar, armour, offhand, ender chest and experience, edited in the player file
 * ({@code <world>/players/data/<uuid>.dat} on 26.1, {@code <world>/playerdata} on older layouts) while the target is
 * offline and locked. Online targets first get their cursor and crafting grid cleared through the API (those are not
 * part of the file) and are then kicked, which makes the server save the file.
 */
public final class VanillaIntegration implements LiveIntegration {

    private final Path dataDir;
    private final Path advancementsDir;
    private final Path statsDir;
    private final PlayerDataEditor.Options options;
    private final boolean advancements;
    private final boolean statistics;
    private final Executor io;

    public VanillaIntegration(Path worldFolder, PlayerDataEditor.Options options, boolean advancements, boolean statistics,
                              Executor io) {
        Path modern = worldFolder.resolve("players").resolve("data");
        boolean isModern = Files.isDirectory(modern) || !Files.isDirectory(worldFolder.resolve("playerdata"));
        this.dataDir = isModern ? modern : worldFolder.resolve("playerdata");
        this.advancementsDir = isModern ? worldFolder.resolve("players").resolve("advancements") : worldFolder.resolve("advancements");
        this.statsDir = isModern ? worldFolder.resolve("players").resolve("stats") : worldFolder.resolve("stats");
        this.options = options;
        this.advancements = advancements;
        this.statistics = statistics;
        this.io = io;
    }

    @Override
    public String id() {
        return "Vanilla";
    }

    @Override
    public String covers() {
        List<String> parts = new ArrayList<>();
        if (options.inventory()) {
            parts.add("inventory, hotbar, armour, offhand, cursor, crafting grid");
        }
        if (options.enderChest()) {
            parts.add("ender chest");
        }
        if (options.experience()) {
            parts.add("experience");
        }
        if (advancements) {
            parts.add("advancements");
        }
        if (statistics) {
            parts.add("statistics");
        }
        return String.join(", ", parts);
    }

    @Override
    public Availability availability() {
        return Files.isDirectory(dataDir) ? Availability.ok() : Availability.missing("no player data folder at " + dataDir);
    }

    public Path dataDir() {
        return dataDir;
    }

    Path fileOf(UUID uuid) {
        return dataDir.resolve(uuid + ".dat");
    }

    @Override
    public Preview preview(UUID target) throws Exception {
        Preview p = new Preview();
        Path f = fileOf(target);
        if (!Files.exists(f)) {
            return p;
        }
        PlayerDataEditor.Result r = PlayerDataEditor.inspect(Nbt.readGzip(Files.readAllBytes(f)).compound(), options);
        if (r.changedAnything()) {
            p.line("player file", r.summary() + " (as last saved; online players are re-read after the kick)");
        }
        if (advancements && Files.exists(advancementsDir.resolve(target + ".json"))) {
            p.line("advancements", "reset");
        }
        if (statistics && Files.exists(statsDir.resolve(target + ".json"))) {
            p.line("statistics", "reset");
        }
        return p;
    }

    @Override
    public Preview previewAll() throws Exception {
        Preview p = new Preview();
        p.line("player files", allFiles().size());
        return p;
    }

    public List<UUID> allPlayers() throws IOException {
        List<UUID> out = new ArrayList<>();
        for (Path f : allFiles()) {
            String n = f.getFileName().toString();
            try {
                out.add(UUID.fromString(n.substring(0, n.length() - 4)));
            } catch (IllegalArgumentException ignored) {
                // not a player file
            }
        }
        return out;
    }

    private List<Path> allFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dataDir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dataDir, "*.dat")) {
            ds.forEach(out::add);
        }
        return out;
    }

    /**
     * Main thread, before the kick: saves the cursor, crafting grid and the live inventories to the backup and clears
     * the cursor and crafting grid, which would otherwise be pushed back into the inventory or dropped on the ground.
     */
    public void prepareOnline(Player player, Path backupDir) throws IOException {
        ItemStack cursor = player.getItemOnCursor();
        List<ItemStack> crafting = new ArrayList<>();
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.WORKBENCH) {
            for (ItemStack it : top.getContents()) {
                if (it != null && !it.isEmpty()) {
                    crafting.add(it.clone());
                }
            }
        }
        List<ItemStack> live = new ArrayList<>();
        for (ItemStack it : player.getInventory().getContents()) {
            live.add(it == null ? ItemStack.empty() : it.clone());
        }
        List<ItemStack> ender = new ArrayList<>();
        for (ItemStack it : player.getEnderChest().getContents()) {
            ender.add(it == null ? ItemStack.empty() : it.clone());
        }
        Files.createDirectories(backupDir);
        Backups.writeOnce(backupDir.resolve("online-inventory.items"), ItemStack.serializeItemsAsBytes(live));
        Backups.writeOnce(backupDir.resolve("online-enderchest.items"), ItemStack.serializeItemsAsBytes(ender));
        List<ItemStack> loose = new ArrayList<>(crafting);
        if (cursor != null && !cursor.isEmpty()) {
            loose.add(cursor.clone());
        }
        Backups.writeOnce(backupDir.resolve("online-cursor-and-crafting.items"), ItemStack.serializeItemsAsBytes(loose));
        if (options.inventory()) {
            player.setItemOnCursor(null);
            if (top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.WORKBENCH) {
                top.clear();
            }
        }
    }

    @Override
    public CompletableFuture<StepResult> wipe(StepContext ctx, UUID target) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return wipeFile(ctx, target);
            } catch (Exception e) {
                return StepResult.failed(e);
            }
        }, io);
    }

    StepResult wipeFile(StepContext ctx, UUID target) throws IOException {
        Path f = fileOf(target);
        Path dir = ctx.backupDirCreated();
        List<String> done = new ArrayList<>();
        if (Files.exists(f)) {
            byte[] original = Files.readAllBytes(f);
            Backups.writeOnce(dir.resolve(target + ".dat"), original);
            Nbt.Root root = Nbt.readGzip(original);
            PlayerDataEditor.Result r = PlayerDataEditor.apply(root.compound(), options);
            if (r.changedAnything()) {
                Backups.replace(f, Nbt.writeGzip(root));
                // verify what was written
                PlayerDataEditor.Result after = PlayerDataEditor.inspect(Nbt.readGzip(Files.readAllBytes(f)).compound(), options);
                if (after.inventoryStacks() + after.enderStacks() + after.equipmentStacks() > 0) {
                    return StepResult.failed("verification failed: the player file still lists items after writing"
                            + " (the original is in the job backup)");
                }
                done.add(r.summary());
            }
        }
        Path old = dataDir.resolve(target + ".dat_old");
        if (Files.exists(old)) {
            // the previous save still holds the old inventory; the server falls back to it when .dat is unreadable
            Backups.copyOnce(old, dir.resolve(target + ".dat_old"));
            Files.delete(old);
            done.add("previous save removed");
        }
        if (advancements) {
            Path a = advancementsDir.resolve(target + ".json");
            if (Files.exists(a)) {
                Backups.copyOnce(a, dir.resolve("advancements-" + target + ".json"));
                Files.delete(a);
                done.add("advancements");
            }
        }
        if (statistics) {
            Path s = statsDir.resolve(target + ".json");
            if (Files.exists(s)) {
                Backups.copyOnce(s, dir.resolve("stats-" + target + ".json"));
                Files.delete(s);
                done.add("statistics");
            }
        }
        if (!Files.exists(f) && done.isEmpty()) {
            return StepResult.nothing("no player file");
        }
        return done.isEmpty() ? StepResult.nothing("nothing stored") : StepResult.done(String.join(", ", done));
    }

    /** Season reset is done per player (one step per player file) by the job service, not in one call. */
    @Override
    public CompletableFuture<StepResult> wipeAll(StepContext ctx) {
        return CompletableFuture.completedFuture(StepResult.skipped("handled per player"));
    }

    @Override
    public CompletableFuture<StepResult> restore(StepContext ctx, UUID target, Path source) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int n = 0;
                Path dir = ctx.backupDirCreated();
                Path dat = source.resolve(target + ".dat");
                if (Files.exists(dat)) {
                    Backups.copyOnce(fileOf(target), dir.resolve(target + ".dat.replaced"));
                    Backups.replace(fileOf(target), Files.readAllBytes(dat));
                    n++;
                }
                Path adv = source.resolve("advancements-" + target + ".json");
                if (Files.exists(adv)) {
                    Files.createDirectories(advancementsDir);
                    Backups.replace(advancementsDir.resolve(target + ".json"), Files.readAllBytes(adv));
                    n++;
                }
                Path st = source.resolve("stats-" + target + ".json");
                if (Files.exists(st)) {
                    Files.createDirectories(statsDir);
                    Backups.replace(statsDir.resolve(target + ".json"), Files.readAllBytes(st));
                    n++;
                }
                String extra = Files.exists(source.resolve("online-cursor-and-crafting.items"))
                        ? "; cursor/crafting items are in online-cursor-and-crafting.items (give back manually)" : "";
                return n == 0 ? StepResult.nothing("no player file backup") : StepResult.done("restored " + n + " file(s)" + extra);
            } catch (Exception e) {
                return StepResult.failed(e);
            }
        }, io);
    }
}
