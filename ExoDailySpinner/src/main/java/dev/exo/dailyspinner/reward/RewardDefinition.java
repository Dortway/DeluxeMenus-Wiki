package dev.exo.dailyspinner.reward;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A validated, cached reward from rewards.yml.
 *
 * @param itemTemplate amount-1 copy of the reward item (null for command rewards)
 * @param itemData     serialized {@code itemTemplate}
 * @param amount       item amount (1 for command rewards)
 * @param displayItem  item shown in menus and on the reel
 * @param displayNameRaw MiniMessage form of {@code displayName}
 * @param announcement custom broadcast text, empty for default, null to inherit the rarity setting
 */
public record RewardDefinition(String id, double weight, Rarity rarity, RewardType type,
                               ItemStack itemTemplate, byte[] itemData, int amount, List<String> commands,
                               ItemStack displayItem, Component displayName, String displayNameRaw,
                               Boolean announce, String announcement) {

    public ItemStack displayItem() {
        return displayItem.clone();
    }

    public boolean shouldAnnounce() {
        return announce != null ? announce : rarity.announce();
    }
}
