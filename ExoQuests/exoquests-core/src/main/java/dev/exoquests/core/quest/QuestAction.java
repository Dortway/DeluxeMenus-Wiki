package dev.exoquests.core.quest;

import java.util.Objects;

/**
 * A single qualifying action detected by the platform layer.
 *
 * @param natural false when the block involved was recorded as player-placed; natural-only quests ignore
 *                such actions while other quests still count them
 */
public record QuestAction(QuestType type, String key, int amount, boolean natural) {

    public QuestAction {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    public QuestAction(QuestType type, String key, int amount) {
        this(type, key, amount, true);
    }
}
