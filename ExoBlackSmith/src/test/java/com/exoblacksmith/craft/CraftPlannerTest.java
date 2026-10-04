package com.exoblacksmith.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CraftPlannerTest {
    /** A fake stack: id + whether it is authentic (signed) + amount. */
    record Fake(String id, boolean authentic, int amount, int max) {
    }

    static final StackOps<Fake, String> OPS = new StackOps<>() {
        public boolean isEmpty(Fake s) {
            return s == null || s.amount() <= 0;
        }

        public int amount(Fake s) {
            return s.amount();
        }

        public int maxStack(Fake s) {
            return s.max();
        }

        public Fake withAmount(Fake s, int amount) {
            return new Fake(s.id(), s.authentic(), amount, s.max());
        }

        public boolean similar(Fake a, Fake b) {
            return a.id().equals(b.id()) && a.authentic() == b.authentic();
        }

        public boolean matches(Fake s, String key) {
            return s.authentic() && s.id().equals(key);
        }
    };

    final CraftPlanner<Fake, String> planner = new CraftPlanner<>(OPS);

    static List<Fake> inventory(Fake... stacks) {
        List<Fake> inv = new ArrayList<>(Arrays.asList(new Fake[36]));
        for (int i = 0; i < stacks.length; i++) {
            inv.set(i, stacks[i]);
        }
        return inv;
    }

    static int total(List<Fake> inv, String id, boolean authentic) {
        int n = 0;
        for (Fake f : inv) {
            if (f != null && f.id().equals(id) && f.authentic() == authentic) {
                n += f.amount();
            }
        }
        return n;
    }

    static Map<String, Integer> req(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    @Test
    void consumesExactQuantitiesAcrossSplitStacks() {
        // 120 creeper heads needed, spread over 64 + 40 + 30; 120 skeleton heads over 2x64
        List<Fake> inv = inventory(new Fake("creeper", true, 64, 64), new Fake("skeleton", true, 64, 64),
                new Fake("creeper", true, 40, 64), new Fake("skeleton", true, 64, 64), new Fake("creeper", true, 30, 64));
        CraftPlan<Fake, String> plan = planner.plan(inv, req("creeper", 60, "skeleton", 60),
                List.of(new Fake("blast_rune", true, 1, 64)));
        assertTrue(plan.ok());
        assertEquals(134 - 60, total(plan.contents(), "creeper", true));
        assertEquals(128 - 60, total(plan.contents(), "skeleton", true));
        assertEquals(1, total(plan.contents(), "blast_rune", true));
        assertEquals(120, plan.consumed().stream().mapToInt(Fake::amount).sum());
    }

    @Test
    void forgedLookalikesAreNeverConsumed() {
        List<Fake> inv = inventory(new Fake("creeper", false, 64, 64), new Fake("creeper", true, 10, 64));
        CraftPlan<Fake, String> plan = planner.plan(inv, req("creeper", 15), List.of(new Fake("out", true, 1, 64)));
        assertEquals(CraftPlan.Status.MISSING, plan.status());
        assertEquals(5, plan.missing().get("creeper"));
        assertNull(plan.contents(), "a failed plan must not produce contents to apply");

        CraftPlan<Fake, String> ok = planner.plan(inventory(new Fake("creeper", false, 64, 64), new Fake("creeper", true, 15, 64)),
                req("creeper", 15), List.of(new Fake("out", true, 1, 64)));
        assertTrue(ok.ok());
        assertEquals(64, total(ok.contents(), "creeper", false), "the forged stack is untouched");
        assertEquals(0, total(ok.contents(), "creeper", true));
    }

    @Test
    void rejectsBeforeConsumingWhenOutputDoesNotFit() {
        List<Fake> inv = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            inv.add(new Fake("dirt", true, 64, 64));
        }
        inv.set(0, new Fake("heads", true, 64, 64));
        inv.set(1, new Fake("heads", true, 64, 64)); // consuming 64 frees slot 0
        CraftPlan<Fake, String> fits = planner.plan(inv, req("heads", 64), List.of(new Fake("mask", true, 1, 1)));
        assertTrue(fits.ok(), "the slot freed by consumption can hold the output");

        CraftPlan<Fake, String> full = planner.plan(inv, req("heads", 10), List.of(new Fake("mask", true, 1, 1)));
        assertEquals(CraftPlan.Status.NO_SPACE, full.status());
        assertNull(full.contents());
    }

    @Test
    void outputMergesIntoExistingStacks() {
        List<Fake> inv = inventory(new Fake("gold", true, 4, 64), new Fake("upgrade", true, 60, 64));
        CraftPlan<Fake, String> plan = planner.plan(inv, req("gold", 4), List.of(new Fake("upgrade", true, 8, 64)));
        assertTrue(plan.ok());
        assertEquals(68, total(plan.contents(), "upgrade", true));
        assertEquals(64, plan.contents().get(1).amount());
    }

    @Test
    void oneStackNeverPaysForTwoRequirements() {
        CraftPlanner<Fake, String> permissive = new CraftPlanner<>(new StackOps<>() {
            public boolean isEmpty(Fake s) {
                return OPS.isEmpty(s);
            }

            public int amount(Fake s) {
                return s.amount();
            }

            public int maxStack(Fake s) {
                return s.max();
            }

            public Fake withAmount(Fake s, int a) {
                return OPS.withAmount(s, a);
            }

            public boolean similar(Fake a, Fake b) {
                return OPS.similar(a, b);
            }

            public boolean matches(Fake s, String key) {
                return true; // deliberately broken matcher
            }
        });
        CraftPlan<Fake, String> plan = permissive.plan(inventory(new Fake("x", true, 10, 64)), req("a", 5, "b", 5),
                List.of(new Fake("out", true, 1, 64)));
        assertFalse(plan.ok(), "the second requirement must not reuse the stack claimed by the first");
    }

    @Test
    void repeatedPlanningOnUnchangedInventoryNeverDoublePays() {
        List<Fake> inv = inventory(new Fake("u", true, 15, 64));
        CraftPlan<Fake, String> first = planner.plan(inv, req("u", 15), List.of(new Fake("out", true, 1, 64)));
        assertTrue(first.ok());
        CraftPlan<Fake, String> second = planner.plan(first.contents(), req("u", 15), List.of(new Fake("out", true, 1, 64)));
        assertEquals(CraftPlan.Status.MISSING, second.status(), "a second click after paying finds nothing to pay with");
        assertEquals(1, total(first.contents(), "out", true));
    }
}
