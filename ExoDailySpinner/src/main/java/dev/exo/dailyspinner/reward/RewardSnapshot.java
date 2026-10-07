package dev.exo.dailyspinner.reward;

import java.util.List;
import java.util.Objects;

/**
 * Immutable copy of everything needed to deliver and display a reward. A snapshot is persisted with
 * the spin before the animation starts, so later configuration edits can never change it.
 *
 * @param itemData     serialized item template (amount 1) for item rewards, otherwise {@code null}
 * @param amount       total item amount for item rewards
 * @param commands     console commands for command rewards (empty for items)
 * @param displayData  serialized display item used by the animation and messages
 * @param displayName  MiniMessage display name
 * @param announcement MiniMessage broadcast override, empty for default, {@code null} for no broadcast
 */
public record RewardSnapshot(
        String rewardId,
        RewardType type,
        byte[] itemData,
        int amount,
        List<String> commands,
        byte[] displayData,
        String displayName,
        String rarityId,
        String announcement) {

    public RewardSnapshot {
        Objects.requireNonNull(rewardId, "rewardId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(rarityId, "rarityId");
        commands = commands == null ? List.of() : List.copyOf(commands);
        if (type == RewardType.ITEM && (itemData == null || amount < 1)) {
            throw new IllegalArgumentException("item reward requires item data and a positive amount");
        }
        if (type == RewardType.COMMAND && commands.isEmpty()) {
            throw new IllegalArgumentException("command reward requires at least one command");
        }
    }

    public String commandsAsText() {
        return String.join("\n", commands);
    }

    public static List<String> commandsFromText(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\n"));
    }
}
