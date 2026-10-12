package dev.exo.cleanwipe.integrations;

import static dev.exo.cleanwipe.Rows.insert;
import static dev.exo.cleanwipe.Rows.m;
import static dev.exo.cleanwipe.TestDb.count;
import static dev.exo.cleanwipe.TestDb.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import dev.exo.cleanwipe.TestDb;
import dev.exo.cleanwipe.integrations.files.ExoVaultsIntegration;
import dev.exo.cleanwipe.integrations.live.LiveSqlIntegration;
import dev.exo.cleanwipe.integrations.live.LiveVaults;
import dev.exo.cleanwipe.integrations.sql.PluginSpecs;
import dev.exo.cleanwipe.integrations.sql.SpecIntegration;
import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.storage.Records.JobState;
import dev.exo.cleanwipe.storage.Records.JobType;
import dev.exo.cleanwipe.storage.Records.Phase;
import dev.exo.cleanwipe.storage.Records.StepState;
import dev.exo.cleanwipe.wipe.IntegrationRegistry;
import dev.exo.cleanwipe.wipe.JobEngine;
import dev.exo.cleanwipe.wipe.StepResult;
import dev.exo.cleanwipe.wipe.WipeOptions;
import dev.exo.vaults.storage.LoadResult;
import dev.exo.vaults.storage.PlayerRecord;
import dev.exo.vaults.storage.RecordRepository;
import dev.exo.vaults.storage.SlotData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Live (no restart) wipes. ExoVaults is exercised with its real RecordRepository and PlayerRecord classes from the
 * installed jar; the database plugins with their real schemas.
 */
class LiveWipeTest {

    static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path dir;

    MockedStatic<Bukkit> bukkit;
    PluginManager pm;

    /** Stands in for ExoVaultsPlugin: same member names as the installed version. */
    public abstract static class FakeVaultsPlugin implements Plugin {
        public Object service;

        public Object vaults() {
            return service;
        }
    }

    public static final class FakeService {
        final Object repository;
        final FakeQueue saveQueue = new FakeQueue();
        final Map<UUID, Object> preloaded = new ConcurrentHashMap<>();
        Object loaded;

        FakeService(Object repository) {
            this.repository = repository;
        }

        public Object data(UUID owner) {
            return loaded;
        }
    }

    public static final class FakeQueue {
        boolean pending;

        public boolean hasPending(UUID owner) {
            return pending;
        }
    }

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        pm = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private Plugin runningPlugin(String name) {
        Plugin p = mock(Plugin.class);
        when(p.isEnabled()).thenReturn(true);
        when(pm.getPlugin(name)).thenReturn(p);
        return p;
    }

    @Test
    void vaultsAreEmptiedLiveAndExoVaultsCanStillSaveAfterwards() throws Exception {
        // ExoVaults' repository root is plugins/ExoVaults; records live in its data/ folder
        Path root = Files.createDirectories(dir.resolve("ExoVaults"));
        Path data = root.resolve("data");
        RecordRepository repo = new RecordRepository(root, false, Logger.getLogger("t"));
        TreeMap<Integer, List<SlotData>> vaults = new TreeMap<>();
        vaults.put(1, List.of(new SlotData(0, new byte[]{1, 2, 3}), new SlotData(5, new byte[]{4})));
        vaults.put(3, List.of(new SlotData(10, new byte[]{9})));
        assertTrue(repo.write(new PlayerRecord(T, 41, 1L, vaults, List.of(new SlotData(0, new byte[]{7})), true)));
        assertTrue(repo.write(new PlayerRecord(T, 42, 2L, vaults, List.of(), true)), "second write creates the .bak copy");
        assertTrue(Files.exists(data.resolve(T + ".dat.bak")));

        FakeVaultsPlugin plugin = mock(FakeVaultsPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doReturn(true).when(plugin).isEnabled();
        FakeService service = new FakeService(repo);
        plugin.service = service;
        when(pm.getPlugin("ExoVaults")).thenReturn(plugin);
        LiveVaults live = new LiveVaults(new ExoVaultsIntegration(data), data, Runnable::run);

        service.loaded = new Object();
        assertEquals(StepState.DEFERRED, live.wipe(ctx(dir.resolve("b0")), T).get().state(), "vaults still loaded");
        service.loaded = null;
        service.saveQueue.pending = true;
        assertEquals(StepState.DEFERRED, live.wipe(ctx(dir.resolve("b0")), T).get().state(), "pending ExoVaults write");
        service.saveQueue.pending = false;

        StepResult r = live.wipe(ctx(dir.resolve("b")), T).get();
        assertEquals(StepState.DONE, r.state(), r.detail());
        LoadResult loaded = repo.load(T);
        PlayerRecord rec = ((LoadResult.Found) loaded).record();
        assertTrue(rec.vaults().isEmpty(), "all vaults empty");
        assertTrue(rec.inventory().isEmpty(), "no inventory checkpoint left to restore");
        assertEquals(43, rec.revision(), "revision chain continues");
        assertFalse(Files.exists(data.resolve(T + ".dat.bak")), "old copy with the items removed");
        assertTrue(Files.exists(dir.resolve("b").resolve(T + ".dat")), "original record backed up");

        // the bug a plain delete would cause: ExoVaults' next save of this player must still be accepted
        assertTrue(repo.write(new PlayerRecord(T, 44, 3L, new TreeMap<>(), List.of(), true)), "ExoVaults can save again");
        assertEquals(StepState.DONE, live.wipe(ctx(dir.resolve("b")), T).get().state(), "repeat is harmless");
    }

    @Test
    void databasePluginIsWipedLiveAndVerified() throws Exception {
        Path db = TestDb.create(dir, "ExoQuests");
        insert(db, "players", m("uuid", T.toString(), "points", 500, "updated_at", 1));
        runningPlugin("ExoQuests");
        SpecIntegration spec = PluginSpecs.create("ExoQuests", db).orElseThrow();
        LiveSqlIntegration live = new LiveSqlIntegration(spec, "ExoQuests", null, false, Executors.newSingleThreadExecutor(), 50);
        StepResult r = live.wipe(ctx(dir.resolve("b")), T).get(10, TimeUnit.SECONDS);
        assertEquals(StepState.DONE, r.state(), r.detail());
        assertEquals(0, count(db, "SELECT COUNT(*) FROM players"));
        assertTrue(Files.exists(dir.resolve("b").resolve(db.getFileName() + ".before")), "consistent online backup");
    }

    @Test
    void dataWrittenBackByThePluginMovesTheStepToTheNextRestart() throws Exception {
        Path db = TestDb.create(dir, "ExoQuests");
        insert(db, "players", m("uuid", T.toString(), "points", 500, "updated_at", 1));
        runningPlugin("ExoQuests");
        // the plugin keeps saving the player's points back from memory
        ScheduledExecutorService plugin = Executors.newSingleThreadScheduledExecutor();
        plugin.scheduleAtFixedRate(() -> {
            try {
                TestDb.exec(db, "INSERT OR IGNORE INTO players (uuid, points, updated_at) VALUES (?, 500, 1)", T.toString());
            } catch (Exception ignored) {
                // busy, retry next time
            }
        }, 10, 10, TimeUnit.MILLISECONDS);
        try {
            LiveSqlIntegration live = new LiveSqlIntegration(PluginSpecs.create("ExoQuests", db).orElseThrow(), "ExoQuests", null, false,
                    Executors.newSingleThreadExecutor(), 80);
            StepResult r = live.wipe(ctx(dir.resolve("b")), T).get(10, TimeUnit.SECONDS);
            assertEquals(StepState.DEFERRED, r.state(), r.detail());
            assertTrue(r.detail().contains("wrote the player's data back"), r.detail());
        } finally {
            plugin.shutdownNow();
        }
    }

    @Test
    void ownedObjectsWithoutALiveRemovalWaitForARestart() throws Exception {
        Path db = TestDb.create(dir, "ExoCubes");
        runningPlugin("ExoCubes");
        LiveSqlIntegration live = new LiveSqlIntegration(PluginSpecs.create("ExoCubes", db).orElseThrow(), "ExoCubes", null, true,
                Executors.newSingleThreadExecutor(), 20);
        assertEquals(StepState.NOTHING, live.wipe(ctx(dir.resolve("b0")), T).get(10, TimeUnit.SECONDS).state(), "owns none: done live");
        insert(db, "cubes", m("id", "c1", "owner", T.toString(), "type", "iron", "state", "ITEM", "token", "t", "size", 3,
                "created_at", 1, "updated_at", 1));
        StepResult r = live.wipe(ctx(dir.resolve("b")), T).get(10, TimeUnit.SECONDS);
        assertEquals(StepState.DEFERRED, r.state());
        assertEquals(1, count(db, "SELECT COUNT(*) FROM cubes"), "nothing changed behind ExoCubes' back");
    }

    @Test
    void blockersStopTheMemoryHookBeforeItRuns() throws Exception {
        Path db = TestDb.create(dir, "ExoWoodCube");
        insert(db, "cubes", m("id", "c1", "owner", T.toString(), "tier", "starter", "size", 5, "state", "ITEM", "token", "t1",
                "created_at", 1, "updated_at", 1));
        insert(db, "ledger", m("cube_id", "c1", "actor", T.toString(), "kind", "SALE", "amount", 1, "status", "PENDING",
                "created_at", 1, "updated_at", 1));
        runningPlugin("ExoWoodCube");
        AtomicBoolean hookRan = new AtomicBoolean();
        LiveSqlIntegration live = new LiveSqlIntegration(PluginSpecs.create("ExoWoodCube", db).orElseThrow(), "ExoWoodCube",
                (owner, target, base) -> {
                    hookRan.set(true);
                    return null;
                }, false, Executors.newSingleThreadExecutor(), 20);
        assertEquals(StepState.BLOCKED, live.wipe(ctx(dir.resolve("b")), T).get(10, TimeUnit.SECONDS).state());
        assertFalse(hookRan.get());
    }

    @Test
    void pluginNotRunningIsWipedDirectly() throws Exception {
        Path db = TestDb.create(dir, "ExoSkills");
        insert(db, "progress", m("uuid", T.toString(), "skill", "mining", "level", 9, "xp", 1.0));
        LiveSqlIntegration live = new LiveSqlIntegration(PluginSpecs.create("ExoSkills", db).orElseThrow(), "ExoSkills",
                (owner, target, base) -> "hook must not run", false, Runnable::run, 20);
        assertEquals(StepState.DONE, live.wipe(ctx(dir.resolve("b")), T).get(10, TimeUnit.SECONDS).state());
        assertEquals(0, count(db, "SELECT COUNT(*) FROM progress"));
    }

    @Test
    void deferredStepRunsAtTheNextStart() throws Exception {
        Path db = TestDb.create(dir, "ExoSkills");
        insert(db, "progress", m("uuid", T.toString(), "skill", "mining", "level", 9, "xp", 1.0));
        IntegrationRegistry reg = new IntegrationRegistry();
        reg.register(PluginSpecs.create("ExoSkills", db).orElseThrow());
        try (CoreStore store = new CoreStore(dir.resolve("core.db"))) {
            JobEngine e = new JobEngine(store, reg, dir.resolve("backups"), Logger.getLogger("t"));
            long job = e.create(JobType.WIPE, null, "Mod", "", WipeOptions.DEFAULT, List.of(new JobEngine.Target(T, "Steve", "primary")),
                    List.of(new JobEngine.StepPlan(T, "ExoSkills", Phase.LIVE)), false, null);
            assertEquals(JobState.RUNNING, e.evaluate(job));
            store.deferToStartup(job, T, "ExoSkills", "next restart: test", System.currentTimeMillis());
            assertEquals(JobState.WAITING_RESTART, e.evaluate(job));
            assertTrue(store.lockOf(T).isPresent(), "stays locked until it ran");
            e.runStartupSteps(true);
            assertEquals(JobState.COMPLETED, store.job(job).orElseThrow().state());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM progress"));
        }
    }
}
