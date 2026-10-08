package dev.exodaily.core.reward;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A reward as configured in rewards.yml: an item, console commands, or both. Instances are immutable and, once assigned to a
 * player, are copied into the database as a snapshot so later configuration edits can never
 * change an assigned reward.
 *
 * @param id              stable reward identifier
 * @param material        Bukkit material name (informational when {@code serializedItem} is set)
 * @param amount          total amount; split into stacks on delivery
 * @param name            MiniMessage display name, or null for the vanilla name
 * @param lore            MiniMessage lore lines
 * @param enchantments    enchantment key to level
 * @param flags           item flag names
 * @param customModelData custom model data float, or null
 * @param summary         short MiniMessage text used in menu summaries, e.g. "16 diamonds"
 * @param weight          default selection weight
 * @param serializedItem  Base64 of Paper's {@code ItemStack#serializeAsBytes}, or null
 * @param type            what claiming does; null (snapshots from before command rewards) means ITEM
 * @param commands        console commands (without leading slash) for COMMAND and BOTH rewards
 */
public record RewardDefinition(
        String id,
        String material,
        int amount,
        String name,
        List<String> lore,
        Map<String, Integer> enchantments,
        List<String> flags,
        Float customModelData,
        String summary,
        int weight,
        String serializedItem,
        RewardType type,
        List<String> commands
) {

    public RewardDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(material, "material");
        lore = lore == null ? List.of() : List.copyOf(lore);
        enchantments = enchantments == null ? Map.of() : Map.copyOf(enchantments);
        flags = flags == null ? List.of() : List.copyOf(flags);
        Objects.requireNonNull(summary, "summary");
        type = type == null ? RewardType.ITEM : type;
        commands = commands == null ? List.of() : List.copyOf(commands);
    }

    /** Convenience constructor for item rewards. */
    public RewardDefinition(String id, String material, int amount, String name, List<String> lore,
                            Map<String, Integer> enchantments, List<String> flags, Float customModelData,
                            String summary, int weight, String serializedItem) {
        this(id, material, amount, name, lore, enchantments, flags, customModelData, summary, weight, serializedItem,
                RewardType.ITEM, List.of());
    }

    public boolean isSerialized() {
        return serializedItem != null && !serializedItem.isEmpty();
    }
}
