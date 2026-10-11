package net.exoeconomy.booster;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.api.BoosterInfo;
import net.exoeconomy.api.BoosterScope;
import net.exoeconomy.config.Messages;
import net.exoeconomy.menu.Items;
import net.exoeconomy.menu.Menu;
import net.exoeconomy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Active, paused and queued boosters affecting the viewer, with remaining time. */
public final class ActiveBoostersMenu extends Menu {
    private static final int BACK = 49;

    public ActiveBoostersMenu(ExoEconomyPlugin plugin, Player viewer) {
        super(plugin, viewer);
        this.inventory = Bukkit.createInventory(this, 54, Text.color(plugin.settings().messages().raw("menus.active.title")));
        render();
    }

    private void render() {
        Messages msg = plugin.settings().messages();
        inventory.clear();
        List<BoosterInfo> list = plugin.boosters().book().snapshot().stream()
                .filter(b -> b.scope() == BoosterScope.GLOBAL || viewer.getUniqueId().equals(b.owner()))
                .sorted(Comparator.comparing(BoosterInfo::scope).thenComparing(BoosterInfo::state)).limit(45).toList();
        int slot = 0;
        for (BoosterInfo b : list) {
            Material icon = switch (b.state()) { case "ACTIVE" -> Material.LIME_DYE; case "PAUSED" -> Material.ORANGE_DYE; default -> Material.GRAY_DYE; };
            String[] values = plugin.boosters().values(b, null);
            String[] all = new String[values.length + 4];
            System.arraycopy(values, 0, all, 0, values.length);
            all[values.length] = "scope";
            all[values.length + 1] = msg.raw("scope-names." + b.scope().name().toLowerCase(Locale.ROOT));
            all[values.length + 2] = "state_name";
            all[values.length + 3] = msg.raw("state-names." + b.state().toLowerCase(Locale.ROOT));
            inventory.setItem(slot++, Items.icon(icon, msg.format("menus.active.booster-name", all), BoosterShopMenu.lines(msg, "menus.active.booster-lore", all)));
        }
        if (list.isEmpty()) inventory.setItem(22, Items.icon(Material.GLASS_BOTTLE, msg.raw("menus.active.empty-name"), List.of()));
        inventory.setItem(BACK, Items.icon(Material.ARROW, msg.raw("menus.back-name"), List.of()));
    }

    @Override
    protected void click(InventoryClickEvent e) {
        if (e.getClickedInventory() != inventory || e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT) return;
        if (e.getSlot() == BACK) later(() -> new BoosterShopMenu(plugin, viewer).open());
    }

    @Override
    protected void tick() { render(); }
}
