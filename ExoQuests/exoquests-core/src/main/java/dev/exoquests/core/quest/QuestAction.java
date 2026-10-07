package dev.exoquests.core.quest;

import java.util.Objects;

/** A single qualifying action detected by the platform layer. */
public record QuestAction(QuestType type, String key, int amount) {

    public QuestAction {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
