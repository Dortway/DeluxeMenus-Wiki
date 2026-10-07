package dev.exoquests.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exoquests.core.shop.SpaceSimulator;
import dev.exoquests.core.shop.SpaceSimulator.Request;
import dev.exoquests.core.shop.SpaceSimulator.Slot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpaceSimulatorTest {

    private static List<Slot> inventory(int empty, Slot... filled) {
        List<Slot> slots = new ArrayList<>(List.of(filled));
        for (int i = 0; i < empty; i++) {
            slots.add(new Slot(null, 0, 64));
        }
        return slots;
    }

    @Test
    void fullInventoryRejects() {
        List<Slot> full = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            full.add(new Slot("dirt", 64, 64));
        }
        assertFalse(SpaceSimulator.fits(full, List.of(new Request("diamond", 4, 64))));
    }

    @Test
    void mergesIntoPartialStacks() {
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("torch", 48, 64));
        for (int i = 0; i < 35; i++) {
            slots.add(new Slot("dirt", 64, 64));
        }
        assertTrue(SpaceSimulator.fits(slots, List.of(new Request("torch", 16, 64))));
        assertFalse(SpaceSimulator.fits(slots, List.of(new Request("torch", 17, 64))));
    }

    @Test
    void unstackableItemsNeedOneSlotEach() {
        assertTrue(SpaceSimulator.fits(inventory(2), List.of(new Request("pick", 1, 1), new Request("pick", 1, 1))));
        assertFalse(SpaceSimulator.fits(inventory(1), List.of(new Request("pick", 1, 1), new Request("pick", 1, 1))));
    }

    @Test
    void largeAmountsSpanSeveralSlots() {
        assertTrue(SpaceSimulator.fits(inventory(2), List.of(new Request("log", 128, 64))));
        assertFalse(SpaceSimulator.fits(inventory(2), List.of(new Request("log", 129, 64))));
    }
}
