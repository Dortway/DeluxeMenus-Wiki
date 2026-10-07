package dev.exoquests.paper.menu;

import static dev.exoquests.paper.text.TextService.n;
import static dev.exoquests.paper.text.TextService.p;

import dev.exoquests.core.config.MenuLayouts;
import dev.exoquests.core.shop.ShopEntry;
import dev.exoquests.paper.ExoQuestsPlugin;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

/** Purchase confirmation bound to a single server-side {@link ConfirmSession}. */
public final class ConfirmMenu extends ExoMenu {

    private final ConfirmSession session;
    private final ShopEntry entry;
    private final long balance;

    ConfirmMenu(ExoQuestsPlugin plugin, Player player, ConfirmSession session, ShopEntry entry, long balance) {
        super(plugin, player);
        this.session = session;
        this.entry = entry;
        this.balance = balance;
        MenuLayouts.ConfirmMenu layout = plugin.configs().current().menus().confirm();
        create(layout.rows(), plugin.text().parse(layout.title(), n("price", entry.price()),
                p("item", plugin.shopEditor().plainName(entry.id()))));
        render();
    }

    ConfirmSession session() {
        return session;
    }

    @Override
    public void render() {
        Player player = player();
        if (player == null) {
            return;
        }
        clear();
        MenuLayouts.ConfirmMenu layout = plugin.configs().current().menus().confirm();
        MenuLayouts.ShopMenu shopLayout = plugin.configs().current().menus().shop();
        TagResolver placeholders = TagResolver.resolver(n("price", entry.price()), n("balance", balance),
                n("after", Math.max(0, balance - entry.price())));
        set(layout.previewSlot(), plugin.menus().previewFor(player, entry, shopLayout, balance), null);
        set(layout.confirm().slot(), button(layout.confirm(), player, placeholders),
                () -> plugin.menus().confirm(player, this));
        set(layout.cancel().slot(), button(layout.cancel(), player, placeholders), () -> {
            plugin.text().play(player, "click");
            plugin.menus().cancelConfirm(player, this);
        });
        fill(layout.filler());
    }
}
