package dev.exoquests.core.shop;

import java.util.List;

/**
 * Platform-neutral inventory-fit check. The caller groups stacks by "similarity" into keys, so the same
 * algorithm used by the server's {@code addItem} (fill similar partial stacks, then empty slots) can be
 * verified without a server.
 */
public final class SpaceSimulator {

    /** A storage slot: {@code key == null} means empty. */
    public record Slot(Object key, int amount, int maxStack) {
    }

    /** Items to add: {@code amount} of {@code key} with the given maximum stack size. */
    public record Request(Object key, int amount, int maxStack) {
    }

    private SpaceSimulator() {
    }

    public static boolean fits(List<Slot> slots, List<Request> requests) {
        Object[] keys = new Object[slots.size()];
        int[] amounts = new int[slots.size()];
        int[] max = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            keys[i] = s.key();
            amounts[i] = s.key() == null ? 0 : s.amount();
            max[i] = s.maxStack();
        }
        for (Request r : requests) {
            if (r.amount() <= 0 || r.maxStack() <= 0) {
                return false;
            }
            int remaining = r.amount();
            for (int i = 0; i < keys.length && remaining > 0; i++) {
                if (keys[i] != null && keys[i].equals(r.key())) {
                    int room = Math.min(max[i], r.maxStack()) - amounts[i];
                    if (room > 0) {
                        int add = Math.min(room, remaining);
                        amounts[i] += add;
                        remaining -= add;
                    }
                }
            }
            for (int i = 0; i < keys.length && remaining > 0; i++) {
                if (keys[i] == null) {
                    int add = Math.min(r.maxStack(), remaining);
                    keys[i] = r.key();
                    amounts[i] = add;
                    max[i] = r.maxStack();
                    remaining -= add;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }
}
