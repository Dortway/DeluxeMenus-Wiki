package dev.exo.cleanwipe.integrations;

import static dev.exo.cleanwipe.Rows.insert;
import static dev.exo.cleanwipe.Rows.m;
import static dev.exo.cleanwipe.TestDb.count;
import static dev.exo.cleanwipe.TestDb.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exo.cleanwipe.TestDb;
import dev.exo.cleanwipe.integrations.files.EGenIntegration;
import dev.exo.cleanwipe.integrations.files.ExoSpawnersIntegration;
import dev.exo.cleanwipe.integrations.files.ExoVaultsIntegration;
import dev.exo.cleanwipe.integrations.sql.ChunkCollectorIntegration;
import dev.exo.cleanwipe.integrations.sql.EPlayerShopIntegration;
import dev.exo.cleanwipe.storage.Records.StepState;
import dev.exo.cleanwipe.wipe.StepResult;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileIntegrationsTest {

    static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID O = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path dir;

    /** Same header layout as ChunkCollector's SnapshotCodec (version 2). */
    static byte[] snapshot(UUID id, UUID owner) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        d.writeInt(2);
        d.writeUTF(id.toString());
        d.writeUTF(owner.toString());
        d.writeUTF("Steve");
        d.writeUTF("PLACED");
        d.writeUTF("");
        d.writeInt(1);
        d.writeInt(2);
        d.writeInt(3);
        for (int i = 0; i < 3; i++) {
            d.writeInt(1);
        }
        d.writeLong(7);
        d.writeInt(1);
        d.writeInt(0);
        d.writeInt(0);
        return b.toByteArray();
    }

    @Test
    void chunkCollectorMatchesOwnerFromSnapshotHeader() throws Exception {
        Path db = TestDb.create(dir, "ChunkCollector");
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        insert(db, "collectors", m("id", mine.toString(), "snapshot", snapshot(mine, T)));
        insert(db, "collectors", m("id", theirs.toString(), "snapshot", snapshot(theirs, O)));
        insert(db, "collectors", m("id", "junk", "snapshot", new byte[]{0, 0, 0, 9}));
        ChunkCollectorIntegration c = new ChunkCollectorIntegration(db);
        assertEquals("1", c.preview(T).lines().get(0).value());
        assertTrue(c.preview(T).warnings().get(0).contains("unknown format"));
        insert(db, "transactions", m("id", "tx", "collector", mine.toString(), "kind", "WITHDRAW", "phase", "PREPARED", "detail", "d",
                "before_snapshot", new byte[]{1}, "after_snapshot", new byte[]{1}));
        assertEquals(StepState.BLOCKED, c.wipe(ctx(dir.resolve("b0")), T).state());
        TestDb.exec(db, "UPDATE transactions SET phase='DONE'");
        StepResult r = c.wipe(ctx(dir.resolve("b")), T);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals(0, count(db, "SELECT COUNT(*) FROM collectors WHERE id=?", mine.toString()));
        assertEquals(2, count(db, "SELECT COUNT(*) FROM collectors"));
        c.restore(ctx(dir.resolve("r")), dir.resolve("b"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM collectors WHERE id=?", mine.toString()));
    }

    @Test
    void ePlayerShopUsesJsonOwner() throws Exception {
        Path db = TestDb.create(dir, "EPlayerShop");
        insert(db, "shops", m("id", "s1", "json", "{\"id\":\"s1\",\"owner\":\"" + T + "\",\"stock\":64}"));
        insert(db, "shops", m("id", "s2", "json", "{\"id\":\"s2\",\"owner\":\"" + O + "\",\"stock\":1}"));
        insert(db, "parcels", m("id", "p1", "json", "{\"id\":\"p1\",\"owner\":\"" + T + "\",\"amount\":5}"));
        insert(db, "journal", m("id", "j1", "phase", "PREPARED", "json", "{\"shop\":\"s1\",\"buyer\":\"" + O + "\"}"));
        EPlayerShopIntegration s = new EPlayerShopIntegration(db);
        assertEquals(StepState.BLOCKED, s.wipe(ctx(dir.resolve("b0")), T).state(), "a buyer may already have paid");
        TestDb.exec(db, "UPDATE journal SET phase='DONE'");
        StepResult r = s.wipe(ctx(dir.resolve("b")), T);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals(0, count(db, "SELECT COUNT(*) FROM shops WHERE id='s1'"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM shops WHERE id='s2'"));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM parcels"));
    }

    @Test
    void exoVaultsMovesEveryRecordFileAndRestores() throws Exception {
        Path data = Files.createDirectories(dir.resolve("vaults"));
        Files.write(data.resolve(T + ".dat"), new byte[]{1, 2, 3});
        Files.write(data.resolve(T + ".dat.bak"), new byte[]{1, 2});
        Files.write(data.resolve(T + ".dat.corrupt-20260101"), new byte[]{9});
        Files.write(data.resolve(O + ".dat"), new byte[]{4});
        ExoVaultsIntegration v = new ExoVaultsIntegration(data);
        assertTrue(v.preview(T).lines().get(0).value().startsWith("3"));
        StepResult r = v.wipe(ctx(dir.resolve("b")), T);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertFalse(Files.exists(data.resolve(T + ".dat")));
        assertFalse(Files.exists(data.resolve(T + ".dat.bak")), "backup copy would be used by ExoVaults if left");
        assertTrue(Files.exists(data.resolve(O + ".dat")));
        assertEquals(StepState.NOTHING, v.wipe(ctx(dir.resolve("b")), T).state(), "repeat is harmless");
        assertEquals(StepState.DONE, v.restore(ctx(dir.resolve("r")), dir.resolve("b")).state());
        assertTrue(Files.exists(data.resolve(T + ".dat")));
    }

    @Test
    void eGenEmptiesOwnedChestsOnly() throws Exception {
        Path machines = dir.resolve("machines.yml");
        Path chests = dir.resolve("chestdata.yml");
        Path limits = dir.resolve("playerdata.yml");
        String mine = UUID.randomUUID().toString();
        String theirs = UUID.randomUUID().toString();
        Files.writeString(machines, "data-version: 2\nmachines:\n  " + mine + ":\n    type: CHEST\n    owner: " + T + "\n  "
                + theirs + ":\n    type: CHEST\n    owner: " + O + "\n");
        String item = "    - ==: org.bukkit.inventory.ItemStack\n      DataVersion: 4790\n      id: minecraft:diamond\n      count: 64\n";
        Files.writeString(chests, "data-version: 1\nchests:\n  " + mine + ":\n    contents:\n" + item.replace("    -", "    -")
                + "  " + theirs + ":\n    contents:\n" + item);
        Files.writeString(limits, "limits:\n  " + T + ": 40\n  " + O + ": 10\n");
        EGenIntegration e = new EGenIntegration(machines, chests, limits);
        assertTrue(e.preview(T).lines().get(0).value().startsWith("1"));
        StepResult r = e.wipe(ctx(dir.resolve("b")), T);
        assertEquals(StepState.DONE, r.state(), r.detail());
        String after = Files.readString(chests);
        assertEquals(1, after.split("minecraft:diamond", -1).length - 1, "only the other player's diamonds remain: " + after);
        assertTrue(after.contains("==: org.bukkit.inventory.ItemStack"), "item maps are carried through unchanged");
        assertFalse(Files.readString(limits).contains(T.toString()));
        assertTrue(Files.readString(limits).contains(O.toString()));
        e.restore(ctx(dir.resolve("r")), dir.resolve("b"));
        assertEquals(2, Files.readString(chests).split("minecraft:diamond", -1).length - 1);
        assertTrue(Files.readString(limits).contains(T + ": 40"));
    }

    @Test
    void exoSpawnersRemovesProgress() throws Exception {
        Path players = dir.resolve("players.yml");
        Files.writeString(players, "players:\n  " + T + ":\n    unlocked: 4\n    kills:\n      zombie: 300\n  " + O + ":\n    unlocked: 1\n");
        ExoSpawnersIntegration s = new ExoSpawnersIntegration(players);
        assertEquals(StepState.DONE, s.wipe(ctx(dir.resolve("b")), T).state());
        assertFalse(Files.readString(players).contains(T.toString()));
        assertTrue(Files.readString(players).contains(O.toString()));
        s.restore(ctx(dir.resolve("r")), dir.resolve("b"));
        assertTrue(Files.readString(players).contains("zombie: 300"));
    }
}
