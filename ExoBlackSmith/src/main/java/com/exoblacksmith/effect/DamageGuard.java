package com.exoblacksmith.effect;

/**
 * Marks damage that ExoBlackSmith itself is dealing (e.g. summon hits redirected to the owner) so that
 * bonus-damage listeners skip it. Prevents recursive bonus loops. Main thread only.
 */
public final class DamageGuard {
    private static int depth;

    private DamageGuard() {
    }

    public static boolean active() {
        return depth > 0;
    }

    public static void run(Runnable action) {
        depth++;
        try {
            action.run();
        } finally {
            depth--;
        }
    }
}
