package dev.exoquests.core.shop;

import java.util.List;

/**
 * A purchasable reward.
 *
 * @param revision short hash of the reward contents; a purchase confirmed against an older revision is rejected
 */
public record ShopEntry(
        String id,
        int price,
        RewardType type,
        ItemSpec item,
        List<String> commands,
        String icon,
        String displayName,
        List<String> displayLore,
        String permission,
        boolean enabled,
        String revision) {

    public ShopEntry {
        commands = commands == null ? List.of() : List.copyOf(commands);
        displayLore = displayLore == null ? List.of() : List.copyOf(displayLore);
    }

    public ShopEntry withPrice(int newPrice) {
        return new ShopEntry(id, newPrice, type, item, commands, icon, displayName, displayLore, permission,
                enabled, revision);
    }
}
