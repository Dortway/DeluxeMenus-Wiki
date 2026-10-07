package dev.exo.dailyspinner.storage;

import java.util.UUID;

/** A stored item reward waiting to be claimed. {@code itemData} is a serialized amount-1 template. */
public record PendingItem(long id, UUID player, String spinId, byte[] itemData, int amount,
                          PendingStatus status, long createdAt) {
}
