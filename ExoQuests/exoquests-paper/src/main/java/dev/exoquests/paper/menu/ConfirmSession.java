package dev.exoquests.paper.menu;

import java.util.UUID;

/**
 * Server-side state of one purchase confirmation. A confirmation is valid only while it is the player's
 * current session, has not been used, has not expired and was created under the current configuration
 * generation. It captures the price and reward revision the player saw.
 */
final class ConfirmSession {

    final UUID token = UUID.randomUUID();
    final String itemId;
    final int price;
    final String revision;
    final int page;
    final long createdAt;
    final int generation;
    boolean consumed;

    ConfirmSession(String itemId, int price, String revision, int page, long createdAt, int generation) {
        this.itemId = itemId;
        this.price = price;
        this.revision = revision;
        this.page = page;
        this.createdAt = createdAt;
        this.generation = generation;
    }
}
