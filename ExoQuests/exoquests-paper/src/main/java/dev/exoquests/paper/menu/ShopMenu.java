package dev.exoquests.paper.menu;

import static dev.exoquests.paper.text.TextService.n;
import static dev.exoquests.paper.text.TextService.p;

import dev.exoquests.core.config.MenuLayouts;
import dev.exoquests.core.shop.ShopEntry;
import dev.exoquests.paper.ExoQuestsPlugin;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Paginated quest shop showing balance, reward previews, prices and availability. */
public final class ShopMenu extends ExoMenu {

    private final int page;
    private final int pages;
    private final long balance;

    ShopMenu(ExoQuestsPlugin plugin, Player player, int requestedPage, long balance) {
        super(plugin, player);
        MenuLayouts.ShopMenu layout = plugin.configs().current().menus().shop();
        int count = plugin.configs().current().shop().visible().size();
        int perPage = layout.itemSlots().size();
        this.pages = Math.max(1, (count + perPage - 1) / perPage);
        this.page = Math.max(0, Math.min(requestedPage, pages - 1));
        this.balance = balance;
        create(layout.rows(), plugin.text().parse(layout.title(), p("page", page + 1), p("pages", pages)));
        render();
    }

    int page() {
        return page;
    }

    @Override
    public void render() {
        Player player = player();
        if (player == null) {
            return;
        }
        clear();
        MenuLayouts.ShopMenu layout = plugin.configs().current().menus().shop();
        List<ShopEntry> entries = plugin.configs().current().shop().visible();
        List<Integer> slots = layout.itemSlots();
        int start = page * slots.size();
        if (entries.isEmpty()) {
            set(slots.get(0), displayItem(Material.BARRIER, plugin.text().item(layout.emptyName()), List.of()), null);
        }
        for (int i = 0; i < slots.size() && start + i < entries.size(); i++) {
            ShopEntry entry = entries.get(start + i);
            set(slots.get(i), RewardPreview.build(plugin, entry, layout, player, balance), () -> plugin.menus().selectReward(player, entry, page,
                    balance));
        }
        TagResolver common = n("balance", balance);
        if (page > 0) {
            set(layout.previous().slot(), button(layout.previous(), player, common), () -> {
                plugin.text().play(player, "page");
                plugin.menus().openShop(player, page - 1);
            });
        }
        if (page < pages - 1) {
            set(layout.next().slot(), button(layout.next(), player, common), () -> {
                plugin.text().play(player, "page");
                plugin.menus().openShop(player, page + 1);
            });
        }
        set(layout.back().slot(), button(layout.back(), player, common), () -> {
            plugin.text().play(player, "click");
            plugin.menus().openQuests(player);
        });
        set(layout.balance().slot(), button(layout.balance(), player, common), null);
        set(layout.close().slot(), button(layout.close(), player, common), () -> {
            plugin.text().play(player, "click");
            player.closeInventory();
        });
        fill(layout.filler());
    }
}
