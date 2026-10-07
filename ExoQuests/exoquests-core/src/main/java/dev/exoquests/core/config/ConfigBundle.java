package dev.exoquests.core.config;

import dev.exoquests.core.quest.QuestPool;
import dev.exoquests.core.shop.ShopCatalog;
import java.util.List;

/** A complete, validated configuration snapshot. Swapped atomically on reload. */
public record ConfigBundle(Settings settings, QuestPool quests, ShopCatalog shop, Messages messages,
                           MenuLayouts menus, List<String> warnings) {

    public ConfigBundle withShop(ShopCatalog newShop) {
        return new ConfigBundle(settings, quests, newShop, messages, menus, warnings);
    }
}
