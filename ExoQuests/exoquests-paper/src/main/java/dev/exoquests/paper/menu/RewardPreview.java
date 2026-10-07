package dev.exoquests.paper.menu;

import static dev.exoquests.paper.text.TextService.n;

import dev.exoquests.core.config.MenuLayouts;
import dev.exoquests.core.shop.RewardType;
import dev.exoquests.core.shop.ShopEntry;
import dev.exoquests.paper.ExoQuestsPlugin;
import dev.exoquests.paper.shop.ItemRewards;
import dev.exoquests.paper.text.TextService;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Builds shop reward previews: the real reward item (or command icon) plus price and availability lines. */
final class RewardPreview {

    private RewardPreview() {
    }

    static ItemStack build(ExoQuestsPlugin plugin, ShopEntry entry, MenuLayouts.ShopMenu layout, Player player,
                           long balance) {
        TextService text = plugin.text();
        boolean locked = entry.permission() != null && !player.hasPermission(entry.permission());
        long missing = Math.max(0, entry.price() - balance);
        String status = locked ? layout.statusLocked()
                : missing == 0 ? layout.statusAffordable() : layout.statusExpensive();
        ItemStack item;
        int amount = 1;
        try {
            if (entry.type() == RewardType.ITEM) {
                item = ItemRewards.template(entry.item(), text);
                amount = ItemRewards.totalAmount(entry.item(), item);
                item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
            } else {
                item = new ItemStack(Material.getMaterial(entry.icon()));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "shop reward " + entry.id() + " cannot be displayed", e);
            return ExoMenu.displayItem(Material.BARRIER, text.item("<error>" + entry.id()), List.of());
        }
        TagResolver placeholders = TagResolver.resolver(n("price", entry.price()), n("balance", balance),
                n("missing", missing), n("amount", amount), Placeholder.component("status", text.parse(status)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (entry.displayName() != null) {
                meta.displayName(text.item(entry.displayName(), placeholders));
            }
            List<Component> lore = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) {
                lore.addAll(existing);
            }
            lore.addAll(text.itemLines(entry.displayLore(), placeholders));
            lore.addAll(text.itemLines(layout.entryLore(), placeholders));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }
}
