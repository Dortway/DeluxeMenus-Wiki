package dev.exo.cleanwipe.integrations;

import static dev.exo.cleanwipe.Rows.insert;
import static dev.exo.cleanwipe.Rows.m;
import static dev.exo.cleanwipe.TestDb.count;
import static dev.exo.cleanwipe.TestDb.ctx;
import static dev.exo.cleanwipe.TestDb.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exo.cleanwipe.TestDb;
import dev.exo.cleanwipe.integrations.sql.PluginSpecs;
import dev.exo.cleanwipe.integrations.sql.SpecIntegration;
import dev.exo.cleanwipe.storage.Records.StepState;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StepResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each database integration against the plugin's real schema: the target's rows are removed or marked as the plugin
 * itself would, another player's rows are untouched, history tables stay, unfinished transactions block, the restore
 * script puts everything back, and a second run is harmless.
 */
class PluginSpecsTest {

    static final String T = "11111111-1111-1111-1111-111111111111";
    static final String O = "22222222-2222-2222-2222-222222222222";
    static final UUID TU = UUID.fromString(T);

    @TempDir
    Path dir;

    static java.util.stream.Stream<String> ids() {
        return PluginSpecs.IDS.stream();
    }

    private SpecIntegration spec(String id, Path db) {
        return PluginSpecs.create(id, db).orElseThrow();
    }

    /** Every rule is valid SQL for the installed version's schema (empty database, all modes). */
    @ParameterizedTest
    @MethodSource("ids")
    void everyRuleRunsAgainstRealSchema(String id) throws Exception {
        Path db = TestDb.create(dir, id);
        SpecIntegration s = spec(id, db);
        assertTrue(s.availability().available());
        Preview p = s.preview(TU);
        assertTrue(p.blockers().isEmpty(), "no blockers on empty db");
        s.previewAll();
        StepResult r = s.wipe(ctx(dir.resolve("b1")), TU);
        assertEquals(StepState.NOTHING, r.state(), r.detail());
        StepResult all = s.wipeAll(ctx(dir.resolve("b2")));
        assertEquals(StepState.NOTHING, all.state(), all.detail());
        assertTrue(Files.exists(dir.resolve("b1").resolve("restore.sql")));
        assertTrue(Files.exists(dir.resolve("b1").resolve(db.getFileName() + ".before")));
    }

    @Test
    void schemaMismatchFailsInsteadOfGuessing() throws Exception {
        Path db = dir.resolve("other.db");
        TestDb.exec(db, "CREATE TABLE unrelated (x INTEGER)");
        SpecIntegration s = spec("ExoSkills", db);
        assertFalse(s.preview(TU).blockers().isEmpty());
        StepResult r = s.wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.FAILED, r.state());
        assertTrue(r.detail().contains("missing table"), r.detail());
    }

    @Test
    void missingDatabaseIsNothingStored() throws Exception {
        SpecIntegration s = spec("ExoSkills", dir.resolve("absent.db"));
        assertFalse(s.availability().available());
        assertEquals(StepState.NOTHING, s.wipe(ctx(dir.resolve("b")), TU).state());
    }

    @Test
    void exoSkills() throws Exception {
        Path db = TestDb.create(dir, "ExoSkills");
        insert(db, "progress", m("uuid", T, "skill", "mining", "level", 40, "xp", 12.5));
        insert(db, "progress", m("uuid", O, "skill", "mining", "level", 3, "xp", 1.0));
        insert(db, "names", m("uuid", T, "name", "Steve"));
        SpecIntegration s = spec("ExoSkills", db);
        assertEquals("1", s.preview(TU).lines().get(0).value());
        StepResult r = s.wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.DONE, r.state());
        assertEquals(0, count(db, "SELECT COUNT(*) FROM progress WHERE uuid=?", T));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM progress WHERE uuid=?", O));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM names"), "name cache kept");
        // idempotent
        assertEquals(StepState.DONE, s.wipe(ctx(dir.resolve("b")), TU).state(), "second attempt reports earlier success");
        // restore
        StepResult rs = s.restore(ctx(dir.resolve("r")), dir.resolve("b"));
        assertEquals(StepState.DONE, rs.state());
        assertEquals("40", text(db, "SELECT level FROM progress WHERE uuid=?", T));
    }

    @Test
    void exoWoodCubeMarksRemovedAndClearsStorage() throws Exception {
        Path db = TestDb.create(dir, "ExoWoodCube");
        insert(db, "cubes", m("id", "c1", "owner", T, "tier", "starter", "size", 5, "state", "PLACED", "world", "eskyblock",
                "x", 10, "y", 64, "z", 10, "facing", "NORTH", "created_at", 1, "updated_at", 1));
        insert(db, "cubes", m("id", "c2", "owner", T, "tier", "starter", "size", 5, "state", "ITEM", "token", "tok2",
                "created_at", 1, "updated_at", 1));
        insert(db, "cubes", m("id", "c3", "owner", O, "tier", "starter", "size", 5, "state", "ITEM", "token", "tok3",
                "created_at", 1, "updated_at", 1));
        insert(db, "cube_items", m("cube_id", "c1", "material", "OAK_LOG", "amount", 640));
        insert(db, "cube_items", m("cube_id", "c3", "material", "OAK_LOG", "amount", 5));
        insert(db, "ledger", m("cube_id", "c1", "actor", T, "kind", "SALE", "amount", 10, "status", "COMPLETED", "created_at", 1, "updated_at", 1));
        SpecIntegration s = spec("ExoWoodCube", db);
        Preview p = s.preview(TU);
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("eskyblock 10 64 10")), p.warnings().toString());
        StepResult r = s.wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals(2, count(db, "SELECT COUNT(*) FROM cubes WHERE owner=? AND state='REMOVED' AND token IS NULL", T));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM cube_items WHERE cube_id='c1'"));
        assertEquals("ITEM", text(db, "SELECT state FROM cubes WHERE id='c3'"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM cube_items WHERE cube_id='c3'"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM ledger"), "ledger history kept");
        s.restore(ctx(dir.resolve("r")), dir.resolve("b"));
        assertEquals("PLACED", text(db, "SELECT state FROM cubes WHERE id='c1'"));
        assertEquals("tok2", text(db, "SELECT token FROM cubes WHERE id='c2'"));
        assertEquals("640", text(db, "SELECT amount FROM cube_items WHERE cube_id='c1'"));
    }

    @Test
    void openLedgerBlocksSinglePlayerButNotSeasonReset() throws Exception {
        Path db = TestDb.create(dir, "ExoWoodCube");
        insert(db, "cubes", m("id", "c1", "owner", T, "tier", "starter", "size", 5, "state", "ITEM", "token", "t1", "created_at", 1, "updated_at", 1));
        insert(db, "ledger", m("cube_id", "c1", "actor", O, "payee", T, "kind", "SALE", "amount", 10, "status", "AMBIGUOUS", "created_at", 1, "updated_at", 1));
        SpecIntegration s = spec("ExoWoodCube", db);
        assertFalse(s.preview(TU).blockers().isEmpty());
        StepResult r = s.wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.BLOCKED, r.state());
        assertTrue(r.detail().contains("open wood cube ledger"), r.detail());
        assertEquals("ITEM", text(db, "SELECT state FROM cubes WHERE id='c1'"), "nothing changed while blocked");
        assertEquals(StepState.DONE, s.wipeAll(ctx(dir.resolve("all"))).state());
    }

    @Test
    void exoCubesDeletesCubesAndStoredOres() throws Exception {
        Path db = TestDb.create(dir, "ExoCubes");
        insert(db, "cubes", m("id", "c1", "owner", T, "type", "iron", "state", "PLACED", "token", "t", "world", "eskyblock",
                "x", 1, "y", 2, "z", 3, "size", 3, "created_at", 1, "updated_at", 1));
        insert(db, "cube_items", m("cube_id", "c1", "material", "IRON_ORE", "amount", 99));
        insert(db, "cubes", m("id", "c2", "owner", O, "type", "iron", "state", "ITEM", "token", "t2", "size", 3, "created_at", 1, "updated_at", 1));
        StepResult r = spec("ExoCubes", db).wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals(0, count(db, "SELECT COUNT(*) FROM cubes WHERE owner=?", T));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM cube_items"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM cubes WHERE owner=?", O));
    }

    @Test
    void exoFarmCubeRevokesTokensAndClearsKelp() throws Exception {
        Path db = TestDb.create(dir, "ExoFarmCube");
        insert(db, "cubes", m("id", "f1", "owner", T, "tier", "STARTER", "state", "PLACED", "world", "w", "x", 1, "y", 1, "z", 1,
                "kelp", 500, "created_at", 1, "updated_at", 1));
        insert(db, "tokens", m("id", "tk1", "cube_id", "f1", "state", "ISSUED", "reason", "x", "created_at", 1, "updated_at", 1));
        StepResult r = spec("ExoFarmCube", db).wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals("REMOVED", text(db, "SELECT state FROM cubes WHERE id='f1'"));
        assertEquals("0", text(db, "SELECT kelp FROM cubes WHERE id='f1'"));
        assertEquals("REVOKED", text(db, "SELECT state FROM tokens WHERE id='tk1'"));
    }

    @Test
    void exoTokenShopOnlyBlocks() throws Exception {
        Path db = TestDb.create(dir, "ExoTokenShop");
        insert(db, "transactions", m("id", "tx", "kind", "BUY", "player_uuid", T, "player_name", "Steve", "tokens", 5,
                "state", "DEBITED", "created_at", 1, "updated_at", 1));
        StepResult r = spec("ExoTokenShop", db).wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.BLOCKED, r.state());
        TestDb.exec(db, "UPDATE transactions SET state='COMPLETED'");
        assertEquals(StepState.NOTHING, spec("ExoTokenShop", db).wipe(ctx(dir.resolve("b2")), TU).state());
        assertEquals(1, count(db, "SELECT COUNT(*) FROM transactions"), "history kept");
    }

    @Test
    void exoNightMarketCancelsDeliveriesAndVoidsQueued() throws Exception {
        Path db = TestDb.create(dir, "ExoNightMarket");
        insert(db, "rotations", m("started_at", 1, "ends_at", 2, "active", 1));
        insert(db, "transactions", m("id", "tx1", "player_uuid", T, "rotation_id", 1, "slot", 0, "item_id", "i", "quantity", 1,
                "currency_id", "money", "price", "1", "discount", 0, "state", "QUEUED", "created_at", 1, "updated_at", 1));
        insert(db, "deliveries", m("player_uuid", T, "transaction_id", "tx1", "amount", 3, "created_at", 1));
        insert(db, "deliveries", m("player_uuid", O, "transaction_id", "tx1", "amount", 3, "created_at", 1));
        StepResult r = spec("ExoNightMarket", db).wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals(1, count(db, "SELECT COUNT(*) FROM deliveries WHERE player_uuid=? AND cancelled_at IS NOT NULL", T));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM deliveries WHERE player_uuid=? AND cancelled_at IS NULL", O));
        assertEquals("VOIDED", text(db, "SELECT state FROM transactions WHERE id='tx1'"));
    }

    @Test
    void exoMultiToolsQuarantinesToolsAndRemovesShards() throws Exception {
        Path db = TestDb.create(dir, "ExoMultiTools");
        insert(db, "players", m("uuid", T, "name", "Steve", "shards", 900, "updated_at", 1));
        insert(db, "tools", m("id", "mt1", "owner", T, "active_mode", "pickaxe", "nonce", 1, "status", "ACTIVE", "created_at", 1, "updated_at", 1));
        insert(db, "tool_modes", m("tool_id", "mt1", "mode", "pickaxe", "level", 30, "xp", 5));
        StepResult r = spec("ExoMultiTools", db).wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals("QUARANTINED", text(db, "SELECT status FROM tools WHERE id='mt1'"));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM players WHERE uuid=?", T));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM tool_modes"), "tool progress kept for restore");
    }

    @Test
    void lightZoneInvalidatesBackpacksKeepsContentsForRestore() throws Exception {
        Path db = TestDb.create(dir, "LightZone");
        insert(db, "lz_backpacks", m("id", "bp1", "level", 2, "owner", T, "contents", new byte[]{9, 9}, "created_at", 1, "updated_at", 1));
        insert(db, "lz_backpacks", m("id", "bp2", "level", 2, "owner", O, "contents", new byte[]{7}, "created_at", 1, "updated_at", 1));
        insert(db, "lz_pending_rewards", m("id", "p1", "player", T, "payload", "x", "created_at", 1));
        insert(db, "lz_players", m("uuid", T, "boss_kills", 12, "crates_opened", 4));
        StepResult r = spec("LightZone", db).wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals("1", text(db, "SELECT invalidated FROM lz_backpacks WHERE id='bp1'"));
        assertEquals("0", text(db, "SELECT invalidated FROM lz_backpacks WHERE id='bp2'"));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM lz_pending_rewards"));
        assertEquals("0", text(db, "SELECT boss_kills FROM lz_players WHERE uuid=?", T));
        spec("LightZone", db).restore(ctx(dir.resolve("r")), dir.resolve("b"));
        assertEquals("0", text(db, "SELECT invalidated FROM lz_backpacks WHERE id='bp1'"));
        assertEquals("12", text(db, "SELECT boss_kills FROM lz_players WHERE uuid=?", T));
    }

    @Test
    void exoEconomyClaimsEscrowBoosters() throws Exception {
        Path db = TestDb.create(dir, "ExoEconomy");
        insert(db, "claims", m("id", "c", "player", T, "state", "OPEN", "reason", "sell", "items", new byte[]{1}, "summary", "s", "created", 1));
        insert(db, "escrow", m("session", "s", "player", T, "state", "HELD", "items", new byte[]{1}, "summary", "s", "updated", 1));
        insert(db, "boosters", m("id", "b", "tier", "t", "scope", "PLAYER", "owner", T, "activity", "mining", "multiplier", "2",
                "state", "ACTIVE", "expires_at", 1, "remaining_ms", 1, "sequence", 1, "paused_by_server", 0, "created", 1));
        insert(db, "boosters", m("id", "g", "tier", "t", "scope", "GLOBAL", "activity", "mining", "multiplier", "2",
                "state", "ACTIVE", "expires_at", 1, "remaining_ms", 1, "sequence", 2, "paused_by_server", 0, "created", 1));
        insert(db, "transactions", m("id", "t1", "player", T, "kind", "SELL", "state", "PENDING", "amount", "1", "currency", "money",
                "details", "d", "created", 1, "updated", 1));
        assertEquals(StepState.BLOCKED, spec("ExoEconomy", db).wipe(ctx(dir.resolve("b")), TU).state());
        TestDb.exec(db, "UPDATE transactions SET state='COMMITTED'");
        StepResult r = spec("ExoEconomy", db).wipe(ctx(dir.resolve("b2")), TU);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals(0, count(db, "SELECT COUNT(*) FROM claims") + count(db, "SELECT COUNT(*) FROM escrow"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM boosters WHERE id='g'"), "global booster kept");
        assertEquals(0, count(db, "SELECT COUNT(*) FROM boosters WHERE owner=?", T));
    }

    @Test
    void exoBoxesAndDailySpinnerAndQuestsAndWandsAndDaily() throws Exception {
        Path boxes = TestDb.create(dir, "ExoBoxes");
        insert(boxes, "balances", m("player_uuid", T, "box_id", "lunar", "amount", 5));
        insert(boxes, "openings", m("id", "o1", "player_uuid", T, "box_id", "lunar", "source", "key", "quantity", 1, "roll_count", 1,
                "state", "COMMITTED", "created_at", 1));
        insert(boxes, "opening_rewards", m("opening_id", "o1", "seq", 0, "reward_id", "r", "item", new byte[]{1}, "amount", 1, "count", 1));
        insert(boxes, "openings", m("id", "o2", "player_uuid", T, "box_id", "lunar", "source", "key", "quantity", 1, "roll_count", 1,
                "state", "DELIVERED", "created_at", 1));
        assertEquals(StepState.DONE, spec("ExoBoxes", boxes).wipe(ctx(dir.resolve("b1")), TU).state());
        assertEquals(0, count(boxes, "SELECT COUNT(*) FROM balances"));
        assertEquals(1, count(boxes, "SELECT COUNT(*) FROM openings"), "delivered opening kept as history");
        assertEquals(0, count(boxes, "SELECT COUNT(*) FROM opening_rewards"));

        Path spin = TestDb.create(dir, "ExoDailySpinner");
        insert(spin, "players", m("uuid", T, "bonus_spins", 3, "updated_at", 1));
        insert(spin, "pending_rewards", m("player_uuid", T, "item_data", new byte[]{1}, "amount", 1, "status", "PENDING", "created_at", 1, "updated_at", 1));
        assertEquals(StepState.DONE, spec("ExoDailySpinner", spin).wipe(ctx(dir.resolve("b2")), TU).state());
        insert(spin, "pending_rewards", m("player_uuid", T, "item_data", new byte[]{1}, "amount", 1, "status", "CLAIMING", "created_at", 1, "updated_at", 1));
        assertEquals(StepState.BLOCKED, spec("ExoDailySpinner", spin).wipe(ctx(dir.resolve("b3")), TU).state());

        Path quests = TestDb.create(dir, "ExoQuests");
        insert(quests, "players", m("uuid", T, "points", 120, "updated_at", 1));
        assertEquals(StepState.DONE, spec("ExoQuests", quests).wipe(ctx(dir.resolve("b4")), TU).state());
        assertEquals(0, count(quests, "SELECT COUNT(*) FROM players"));

        Path wands = TestDb.create(dir, "ExoWands");
        insert(wands, "tokens", m("player", T, "balance", 50));
        insert(wands, "build_jobs", m("id", "j", "player", T, "world", "w", "material", "STONE", "positions", new byte[]{1},
                "charged", "1", "free", 0, "status", "RUNNING", "created", 1));
        assertEquals(StepState.BLOCKED, spec("ExoWands", wands).wipe(ctx(dir.resolve("b5")), TU).state());
        assertEquals(1, count(wands, "SELECT COUNT(*) FROM tokens"), "blocked step changed nothing");

        Path daily = TestDb.create(dir, "ExoDaily");
        insert(daily, "players", m("uuid", T, "first_use_date", "2026-01-01", "cycle_number", 2, "cycle_start_date", "2026-01-01",
                "cycle_length", 7, "updated_at", 1));
        assertEquals(StepState.DONE, spec("ExoDaily", daily).wipe(ctx(dir.resolve("b6")), TU).state());
        assertNull(text(daily, "SELECT uuid FROM players"));
    }

    @Test
    void exoToolsRevokes() throws Exception {
        Path db = TestDb.create(dir, "ExoTools");
        insert(db, "tools", m("id", "t1", "owner", T, "material", "DIAMOND_PICKAXE", "level", 50, "charge", 10, "nonce", "n",
                "state", "ACTIVE", "created_at", 1, "updated_at", 1));
        insert(db, "tools", m("id", "t2", "owner", T, "material", "DIAMOND_PICKAXE", "level", 1, "charge", 0, "nonce", "n",
                "state", "DESTROYED", "created_at", 1, "updated_at", 1));
        StepResult r = spec("ExoTools", db).wipe(ctx(dir.resolve("b")), TU);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals("REVOKED", text(db, "SELECT state FROM tools WHERE id='t1'"));
        assertEquals("DESTROYED", text(db, "SELECT state FROM tools WHERE id='t2'"));
    }

    @Test
    void restoreScriptHandlesNewlinesQuotesAndBlobs() throws Exception {
        Path db = TestDb.create(dir, "ExoEconomy");
        String tricky = "line1\nline2 'quoted' ); DROP TABLE claims; --";
        insert(db, "claims", m("id", "c", "player", T, "state", "OPEN", "reason", tricky, "items", new byte[]{0, 1, (byte) 0xFF},
                "summary", "s", "created", 1));
        spec("ExoEconomy", db).wipe(ctx(dir.resolve("b")), TU);
        assertEquals(0, count(db, "SELECT COUNT(*) FROM claims"));
        assertEquals(StepState.DONE, spec("ExoEconomy", db).restore(ctx(dir.resolve("r")), dir.resolve("b")).state());
        assertEquals(tricky, text(db, "SELECT reason FROM claims WHERE id='c'"));
        assertEquals("00 01 FF", text(db, "SELECT upper(substr(hex(items),1,2)) || ' ' || upper(substr(hex(items),3,2)) || ' ' || upper(substr(hex(items),5,2)) FROM claims"));
    }
}
