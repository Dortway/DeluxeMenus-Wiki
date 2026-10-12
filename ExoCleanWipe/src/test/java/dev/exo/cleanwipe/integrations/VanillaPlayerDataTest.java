package dev.exo.cleanwipe.integrations;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exo.cleanwipe.TestDb;
import dev.exo.cleanwipe.integrations.vanilla.Nbt;
import dev.exo.cleanwipe.integrations.vanilla.Nbt.ListTag;
import dev.exo.cleanwipe.integrations.vanilla.Nbt.Tag;
import dev.exo.cleanwipe.integrations.vanilla.PlayerDataEditor;
import dev.exo.cleanwipe.integrations.vanilla.VanillaIntegration;
import dev.exo.cleanwipe.storage.Records.StepState;
import dev.exo.cleanwipe.wipe.StepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline player files in the Minecraft 26.1 layout: world/players/data/<uuid>.dat (gzip NBT). */
class VanillaPlayerDataTest {

    static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path dir;

    static Tag item(String id, int count, int slot) {
        Map<String, Tag> m = new LinkedHashMap<>();
        m.put("id", new Tag(Nbt.STRING, id));
        m.put("count", new Tag(Nbt.INT, count));
        if (slot >= 0) {
            m.put("Slot", new Tag(Nbt.BYTE, (byte) slot));
        }
        return new Tag(Nbt.COMPOUND, m);
    }

    static Tag list(Tag... items) {
        return new Tag(Nbt.LIST, new ListTag(Nbt.COMPOUND, new ArrayList<>(List.of(items))));
    }

    static Map<String, Tag> player() {
        Map<String, Tag> root = new LinkedHashMap<>();
        root.put("DataVersion", new Tag(Nbt.INT, 4790));
        root.put("Inventory", list(item("minecraft:diamond_block", 64, 0), item("minecraft:netherite_ingot", 64, 8)));
        Map<String, Tag> eq = new LinkedHashMap<>();
        eq.put("head", item("minecraft:netherite_helmet", 1, -1));
        eq.put("offhand", item("minecraft:totem_of_undying", 1, -1));
        root.put("equipment", new Tag(Nbt.COMPOUND, eq));
        root.put("EnderItems", list(item("minecraft:shulker_box", 1, 0)));
        root.put("XpLevel", new Tag(Nbt.INT, 30));
        root.put("XpP", new Tag(Nbt.FLOAT, 0.5f));
        root.put("XpTotal", new Tag(Nbt.INT, 1395));
        root.put("Pos", new Tag(Nbt.LIST, new ListTag(Nbt.DOUBLE, new ArrayList<>(List.of(new Tag(Nbt.DOUBLE, 1.5),
                new Tag(Nbt.DOUBLE, 64.0), new Tag(Nbt.DOUBLE, -3.25))))));
        root.put("UUID", new Tag(Nbt.INT_ARRAY, new int[]{1, 2, 3, 4}));
        Map<String, Tag> bukkit = new LinkedHashMap<>();
        bukkit.put("exovaults:vault_revision", new Tag(Nbt.LONG, 41L));
        bukkit.put("exovaults:vault_dirty", new Tag(Nbt.BYTE, (byte) 1));
        bukkit.put("otherplugin:keep", new Tag(Nbt.STRING, "x"));
        root.put("BukkitValues", new Tag(Nbt.COMPOUND, bukkit));
        Map<String, Tag> llama = new LinkedHashMap<>();
        llama.put("id", new Tag(Nbt.STRING, "minecraft:llama"));
        llama.put("Items", list(item("minecraft:diamond", 64, 2)));
        Map<String, Tag> vehicle = new LinkedHashMap<>();
        vehicle.put("Entity", new Tag(Nbt.COMPOUND, llama));
        root.put("RootVehicle", new Tag(Nbt.COMPOUND, vehicle));
        return root;
    }

    private Path writePlayer(Path level) throws IOException {
        Path data = Files.createDirectories(level.resolve("players").resolve("data"));
        Path f = data.resolve(T + ".dat");
        Files.write(f, Nbt.writeGzip(new Nbt.Root("", player())));
        Files.write(data.resolve(T + ".dat_old"), Nbt.writeGzip(new Nbt.Root("", player())));
        Files.createDirectories(level.resolve("players").resolve("advancements"));
        Files.writeString(level.resolve("players").resolve("advancements").resolve(T + ".json"), "{}");
        return f;
    }

    @Test
    void roundTripIsLossless() throws Exception {
        byte[] a = Nbt.writeGzip(new Nbt.Root("", player()));
        Nbt.Root back = Nbt.readGzip(a);
        byte[] b = Nbt.writeGzip(back);
        assertArrayEquals(Nbt.readGzip(a).compound().keySet().toArray(), Nbt.readGzip(b).compound().keySet().toArray());
        assertEquals(-3.25, (Double) back.compound().get("Pos").list().items().get(2).value());
        assertArrayEquals(new int[]{1, 2, 3, 4}, (int[]) back.compound().get("UUID").value());
    }

    @Test
    void editorClearsEveryPossessionAndKeepsTheRest() throws Exception {
        Map<String, Tag> root = player();
        PlayerDataEditor.Result r = PlayerDataEditor.apply(root, new PlayerDataEditor.Options(true, true, true));
        assertEquals(2, r.inventoryStacks());
        assertEquals(2, r.equipmentStacks());
        assertEquals(1, r.enderStacks());
        assertTrue(r.experienceReset());
        assertTrue(r.vehicleRemoved());
        assertEquals(2, r.markersRemoved());
        assertTrue(root.get("Inventory").list().items().isEmpty());
        assertTrue(root.get("EnderItems").list().items().isEmpty());
        assertFalse(root.containsKey("equipment"));
        assertFalse(root.containsKey("RootVehicle"));
        assertEquals(0, root.get("XpLevel").value());
        assertEquals(0f, root.get("XpP").value());
        assertTrue(root.get("BukkitValues").compound().containsKey("otherplugin:keep"));
        assertTrue(root.containsKey("Pos"));
    }

    @Test
    void unknownLayoutIsRefused() {
        Map<String, Tag> root = new LinkedHashMap<>();
        root.put("Something", new Tag(Nbt.INT, 1));
        assertThrows(IOException.class, () -> PlayerDataEditor.apply(root, new PlayerDataEditor.Options(true, true, true)));
    }

    @Test
    void offlineWipeEditsFileRemovesPreviousSaveAndBacksUp() throws Exception {
        Path level = dir.resolve("world");
        Path f = writePlayer(level);
        VanillaIntegration v = new VanillaIntegration(level, new PlayerDataEditor.Options(true, true, true), true, false, Runnable::run);
        assertTrue(v.availability().available());
        assertTrue(v.preview(T).lines().get(0).value().contains("inventory 2"));
        StepResult r = v.wipe(TestDb.ctx(dir.resolve("b")), T).get();
        assertEquals(StepState.DONE, r.state(), r.detail());
        Map<String, Tag> after = Nbt.readGzip(Files.readAllBytes(f)).compound();
        assertTrue(after.get("Inventory").list().items().isEmpty());
        assertFalse(Files.exists(f.resolveSibling(T + ".dat_old")), "the previous save still held the items");
        assertFalse(Files.exists(level.resolve("players/advancements/" + T + ".json")));
        assertTrue(Files.exists(dir.resolve("b").resolve(T + ".dat")));
        // retry keeps the first backup (with items)
        v.wipe(TestDb.ctx(dir.resolve("b")), T).get();
        Map<String, Tag> backup = Nbt.readGzip(Files.readAllBytes(dir.resolve("b").resolve(T + ".dat"))).compound();
        assertEquals(2, backup.get("Inventory").list().items().size());
        // restore
        StepResult rs = v.restore(TestDb.ctx(dir.resolve("r")), T, dir.resolve("b")).get();
        assertEquals(StepState.DONE, rs.state(), rs.detail());
        assertEquals(2, Nbt.readGzip(Files.readAllBytes(f)).compound().get("Inventory").list().items().size());
    }

    @Test
    void missingFileIsNothingStored() throws Exception {
        Path level = dir.resolve("world");
        Files.createDirectories(level.resolve("players/data"));
        VanillaIntegration v = new VanillaIntegration(level, new PlayerDataEditor.Options(true, true, true), false, false, Runnable::run);
        assertEquals(StepState.NOTHING, v.wipe(TestDb.ctx(dir.resolve("b")), T).get().state());
    }
}
