package dev.exoquests.core.storage;

import java.util.UUID;

public record PurchaseRecord(String purchaseId, UUID player, String itemId, int price, String revision,
                             String rewardType, String rewardSnapshot, PurchaseState state, String note,
                             long createdAt, long updatedAt) {
}
