package dev.exoquests.core.shop;

import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Platform side of a purchase. All methods except {@link #mainThread()} are invoked on the main thread.
 */
public interface DeliveryPort {

    enum Check { OK, OFFLINE, NO_PERMISSION, NO_SPACE, INVALID_NAME, UNAVAILABLE }

    Executor mainThread();

    /** Checks that {@code entry} can be handed to the player right now (online, permission, space...). */
    Check precheck(UUID player, ShopEntry entry);

    /** Same check against a durable reward snapshot (used when resuming a pending delivery). */
    Check precheckSnapshot(UUID player, RewardType type, String snapshot);

    /** Encodes the exact reward so a later delivery hands out what was paid for, even after edits. */
    String snapshot(ShopEntry entry);

    /**
     * Hands out the reward. Throwing means the outcome is unknown and the purchase goes to manual review.
     */
    void deliver(UUID player, String purchaseId, String itemId, int price, RewardType type, String snapshot)
            throws Exception;
}
