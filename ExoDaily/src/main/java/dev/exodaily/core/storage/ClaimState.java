package dev.exodaily.core.storage;

/**
 * Durable delivery states of a claim. A database transaction cannot atomically include a
 * Minecraft inventory change, so the state records how far delivery got:
 * <ul>
 *     <li>{@link #RESERVED}: the claim is reserved; nothing has been given yet.</li>
 *     <li>{@link #DELIVERING}: written durably immediately before the inventory change. If the
 *     server stops in this state, delivery is uncertain.</li>
 *     <li>{@link #DELIVERED}: the items were added to the player's inventory.</li>
 *     <li>{@link #UNCERTAIN}: delivery may or may not have happened; never reissued
 *     automatically, awaiting administrative reconciliation.</li>
 * </ul>
 * Any existing row blocks another claim of the same position.
 */
public enum ClaimState {
    RESERVED,
    DELIVERING,
    DELIVERED,
    UNCERTAIN
}
