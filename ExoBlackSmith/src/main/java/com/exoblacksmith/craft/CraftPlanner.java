package com.exoblacksmith.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans an all-or-nothing craft on a copy of the inventory:
 * <ol>
 *   <li>every requirement is satisfied from authentic matching stacks (split stacks are combined);</li>
 *   <li>removals are applied to the copy;</li>
 *   <li>outputs are inserted into the copy; if anything does not fit, the plan is rejected.</li>
 * </ol>
 * The caller applies {@link CraftPlan#contents()} in a single main-thread step, so a failed plan never
 * consumes anything and a successful one consumes and grants exactly once.
 */
public final class CraftPlanner<S, K> {
    private final StackOps<S, K> ops;

    public CraftPlanner(StackOps<S, K> ops) {
        this.ops = ops;
    }

    public CraftPlan<S, K> plan(List<S> inventory, Map<K, Integer> requirements, List<S> outputs) {
        List<S> work = new ArrayList<>(inventory);
        List<S> consumed = new ArrayList<>();
        Map<K, Integer> missing = new LinkedHashMap<>();
        boolean[] claimed = new boolean[work.size()];

        for (Map.Entry<K, Integer> req : requirements.entrySet()) {
            int needed = req.getValue();
            for (int slot = 0; slot < work.size() && needed > 0; slot++) {
                S stack = work.get(slot);
                if (stack == null || ops.isEmpty(stack) || !ops.matches(stack, req.getKey())) {
                    continue;
                }
                if (claimed[slot]) {
                    // a stack may only ever pay for one requirement, even if a matcher were too permissive
                    continue;
                }
                claimed[slot] = true;
                int take = Math.min(needed, ops.amount(stack));
                consumed.add(ops.withAmount(stack, take));
                int left = ops.amount(stack) - take;
                work.set(slot, left > 0 ? ops.withAmount(stack, left) : null);
                needed -= take;
            }
            if (needed > 0) {
                missing.put(req.getKey(), needed);
            }
        }
        if (!missing.isEmpty()) {
            return new CraftPlan<>(CraftPlan.Status.MISSING, null, List.of(), Collections.unmodifiableMap(missing));
        }

        for (S output : outputs) {
            int remaining = ops.amount(output);
            for (int slot = 0; slot < work.size() && remaining > 0; slot++) {
                S stack = work.get(slot);
                if (stack != null && !ops.isEmpty(stack) && ops.similar(stack, output)) {
                    int space = ops.maxStack(stack) - ops.amount(stack);
                    if (space > 0) {
                        int add = Math.min(space, remaining);
                        work.set(slot, ops.withAmount(stack, ops.amount(stack) + add));
                        remaining -= add;
                    }
                }
            }
            for (int slot = 0; slot < work.size() && remaining > 0; slot++) {
                S stack = work.get(slot);
                if (stack == null || ops.isEmpty(stack)) {
                    int add = Math.min(ops.maxStack(output), remaining);
                    work.set(slot, ops.withAmount(output, add));
                    remaining -= add;
                }
            }
            if (remaining > 0) {
                return new CraftPlan<>(CraftPlan.Status.NO_SPACE, null, List.of(), Map.of());
            }
        }
        return new CraftPlan<>(CraftPlan.Status.OK, Collections.unmodifiableList(work),
                Collections.unmodifiableList(consumed), Map.of());
    }
}
