package dev.exodaily.core.delivery;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulates adding stacks to storage slots the same way a Bukkit inventory does (fill similar
 * partial stacks first, then empty slots) without touching the real inventory. Used to decide,
 * before anything is given, whether a reward fits completely; rewards are never dropped.
 */
public final class InventoryFit {

    private InventoryFit() {
    }

    /** Stack operations, so the simulation works with Bukkit items and with test doubles. */
    public interface StackOps<T> {

        boolean isEmpty(T stack);

        int amount(T stack);

        /** Effective maximum stack size for this item in this inventory. */
        int maxStackSize(T stack);

        boolean similar(T a, T b);
    }

    public static <T> boolean fits(List<T> slots, List<T> additions, StackOps<T> ops) {
        int size = slots.size();
        List<T> types = new ArrayList<>(slots);
        int[] amounts = new int[size];
        for (int i = 0; i < size; i++) {
            T stack = slots.get(i);
            amounts[i] = stack == null || ops.isEmpty(stack) ? 0 : ops.amount(stack);
            if (amounts[i] == 0) {
                types.set(i, null);
            }
        }
        for (T addition : additions) {
            if (addition == null || ops.isEmpty(addition)) {
                continue;
            }
            int remaining = ops.amount(addition);
            int max = Math.max(1, ops.maxStackSize(addition));
            for (int i = 0; i < size && remaining > 0; i++) {
                T existing = types.get(i);
                if (existing != null && amounts[i] < max && ops.similar(existing, addition)) {
                    int moved = Math.min(remaining, max - amounts[i]);
                    amounts[i] += moved;
                    remaining -= moved;
                }
            }
            for (int i = 0; i < size && remaining > 0; i++) {
                if (types.get(i) == null) {
                    int moved = Math.min(remaining, max);
                    types.set(i, addition);
                    amounts[i] = moved;
                    remaining -= moved;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }
}
