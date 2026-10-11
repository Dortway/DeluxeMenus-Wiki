package net.exoeconomy.booster;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.config.BoosterSettings;
import net.exoeconomy.config.Messages;
import net.exoeconomy.core.Money;
import net.exoeconomy.core.booster.BoosterSpec;
import net.exoeconomy.menu.Items;
import net.exoeconomy.menu.Menu;
import net.exoeconomy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Purchase confirmation. Holds an immutable snapshot of the tier, so the purchase uses exactly the
 * price, multiplier and duration shown here. The confirm button works once; further clicks
 * (double clicks, macro clicks) are ignored, and the coordinator's per-player lock refuses any
 * second purchase while the first is in flight.
 */
public final class BoosterConfirmMenu extends Menu {
    private final BoosterSettings.Tier tier;
    private final BoosterSettings.Menu layout;
    private boolean used;

    public BoosterConfirmMenu(ExoEconomyPlugin plugin, Player viewer, BoosterSettings.Tier tier) {
        super(plugin, viewer);
        this.tier = tier;
        this.layout = plugin.settings().boosters().menu();
        this.inventory = Bukkit.createInventory(this, 27, Text.color(layout.confirmTitle()));
        Messages msg = plugin.settings().messages();
        ItemStack filler = Items.icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inventory.setItem(i, filler);
        BoosterSpec s = tier.spec();
        String[] values = {"tier", tier.displayName(), "scope", msg.raw("scope-names." + s.scope().name().toLowerCase(Locale.ROOT)),
                "activity", plugin.boosters().activityName(s.activity()), "multiplier", Money.plain(s.multiplier()),
                "duration", Text.duration(s.durationMs()), "price", plugin.ledger().format(s.price())};
        inventory.setItem(layout.summarySlot(), Items.icon(tier.icon(), tier.displayName(), BoosterShopMenu.lines(msg, "menus.confirm.summary-lore", values)));
        inventory.setItem(layout.confirmSlot(), Items.icon(Material.LIME_CONCRETE, msg.raw("menus.confirm.confirm-name"), BoosterShopMenu.lines(msg, "menus.confirm.confirm-lore", values)));
        inventory.setItem(layout.cancelSlot(), Items.icon(Material.RED_CONCRETE, msg.raw("menus.confirm.cancel-name"), List.of()));
    }

    @Override
    protected void click(InventoryClickEvent e) {
        if (e.getClickedInventory() != inventory || (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT)) return;
        if (e.getSlot() == layout.cancelSlot()) { later(() -> new BoosterShopMenu(plugin, viewer).open()); return; }
        if (e.getSlot() != layout.confirmSlot() || used) return;
        used = true;
        inventory.setItem(layout.confirmSlot(), Items.icon(Material.YELLOW_CONCRETE, plugin.settings().messages().raw("menus.confirm.processing-name"), List.of()));
        plugin.boosters().purchase(viewer, tier, success -> {
            later(() -> { if (isOpen()) close(); });
        });
    }
}
