package dev.exoquests.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exoquests.core.storage.Database;
import dev.exoquests.core.storage.PlacementMark;
import dev.exoquests.core.storage.PlacementStore;
import dev.exoquests.core.util.BlockPos;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Anti-farming placement tracking, including persistence across restarts. */
class PlacementStoreTest {

    @TempDir
    Path dir;
    Database db;
    PlacementStore store;
    final String world = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        db = TestSupport.open(dir);
        store = new PlacementStore(db, System::currentTimeMillis);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private BlockPos pos(int x, int y, int z) {
        return new BlockPos(world, x, y, z);
    }

    private int natural(BlockPos p, String material) throws Exception {
        return store.consumeNatural(List.of(new PlacementStore.Target(p, material))).get();
    }

    @Test
    void placedBlockIsNotNaturalAndMarkIsConsumed() throws Exception {
        BlockPos p = pos(1, 64, 1);
        store.place(p, "DIAMOND_ORE", PlacementMark.Kind.PLACED, null).get();
        assertEquals(0, natural(p, "DIAMOND_ORE"));
        // After the break the position is free; a generator filling it later counts again.
        assertEquals(1, natural(p, "DIAMOND_ORE"));
    }

    @Test
    void placementTrackingSurvivesRestart() throws Exception {
        BlockPos p = pos(5, 70, -3);
        store.place(p, "OAK_LOG", PlacementMark.Kind.PLACED, null).get();
        db.close();
        db = TestSupport.open(dir);
        store = new PlacementStore(db, System::currentTimeMillis);
        assertEquals(0, natural(p, "OAK_LOG"));
    }

    @Test
    void repeatedPlaceAndBreakNeverCounts() throws Exception {
        BlockPos p = pos(0, 0, 0);
        int counted = 0;
        for (int i = 0; i < 50; i++) {
            store.place(p, "IRON_ORE", PlacementMark.Kind.PLACED, null);
            counted += natural(p, "IRON_ORE");
        }
        assertEquals(0, counted);
    }

    @Test
    void operationsAreAppliedInCallOrderWithoutWaiting() throws Exception {
        List<CompletableFuture<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            BlockPos p = pos(i, 1, 1);
            store.place(p, "COBBLESTONE", PlacementMark.Kind.PLACED, null);
            results.add(store.consumeNatural(List.of(new PlacementStore.Target(p, "COBBLESTONE"))));
        }
        for (CompletableFuture<Integer> r : results) {
            assertEquals(0, r.get());
        }
        assertEquals(0L, store.count().get());
    }

    @Test
    void staleMarkForDifferentMaterialIsIgnored() throws Exception {
        BlockPos p = pos(2, 2, 2);
        store.place(p, "SUGAR_CANE", PlacementMark.Kind.PLACED, null).get();
        // The cane was removed without a tracked break and a generator later formed cobblestone here.
        assertEquals(1, natural(p, "COBBLESTONE"));
    }

    @Test
    void inPlaceTransformationsKeepTheMark() throws Exception {
        BlockPos log = pos(3, 3, 3);
        store.place(log, "OAK_LOG", PlacementMark.Kind.PLACED, null).get();
        assertEquals(0, natural(log, "STRIPPED_OAK_LOG"));
        BlockPos bamboo = pos(4, 4, 4);
        store.place(bamboo, "BAMBOO_SAPLING", PlacementMark.Kind.PLACED, null).get();
        assertEquals(0, natural(bamboo, "BAMBOO"));
    }

    @Test
    void pistonMovesCarryMarks() throws Exception {
        BlockPos a = pos(0, 10, 0);
        BlockPos b = pos(1, 10, 0);
        BlockPos c = pos(2, 10, 0);
        store.place(a, "GOLD_ORE", PlacementMark.Kind.PLACED, null);
        store.place(b, "COAL_ORE", PlacementMark.Kind.PLACED, null);
        // Push a->b and b->c in one piston event.
        store.move(List.of(new PlacementStore.Move(a, b), new PlacementStore.Move(b, c))).get();
        assertEquals(1, natural(a, "GOLD_ORE"));
        assertEquals(0, natural(b, "GOLD_ORE"));
        assertEquals(0, natural(c, "COAL_ORE"));
    }

    @Test
    void columnCountsOnlyNaturalSegments() throws Exception {
        BlockPos base = pos(9, 60, 9);
        // Player placed the bottom cane and stacked one more on top manually at y+3.
        store.place(base, "SUGAR_CANE", PlacementMark.Kind.PLACED, null);
        store.place(base.offset(0, 3, 0), "SUGAR_CANE", PlacementMark.Kind.PLACED, null);
        List<PlacementStore.Target> column = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            column.add(new PlacementStore.Target(base.offset(0, i, 0), "SUGAR_CANE"));
        }
        assertEquals(2, store.consumeNatural(column).get());
    }

    @Test
    void saplingMarksAreConsumedOnceWithTheirPlanter() throws Exception {
        UUID planter = UUID.randomUUID();
        BlockPos s1 = pos(0, 64, 0);
        BlockPos s2 = pos(1, 64, 0);
        store.place(s1, "DARK_OAK_SAPLING", PlacementMark.Kind.SAPLING, planter);
        store.place(s2, "DARK_OAK_SAPLING", PlacementMark.Kind.SAPLING, planter);
        store.place(pos(5, 64, 5), "STONE", PlacementMark.Kind.PLACED, null);
        List<PlacementMark> found = store.consumeSaplings(List.of(s1, s2, pos(0, 64, 1), pos(5, 64, 5))).get();
        assertEquals(2, found.size());
        assertTrue(found.stream().allMatch(m -> planter.equals(m.owner())));
        assertTrue(store.consumeSaplings(List.of(s1, s2)).get().isEmpty());
        // The non-sapling mark was left alone.
        assertEquals(0, natural(pos(5, 64, 5), "STONE"));
    }

    @Test
    void clearRemovesMarks() throws Exception {
        BlockPos p = pos(7, 7, 7);
        store.place(p, "MELON", PlacementMark.Kind.PLACED, null);
        store.clear(List.of(p)).get();
        assertEquals(1, natural(p, "MELON"));
    }
}
