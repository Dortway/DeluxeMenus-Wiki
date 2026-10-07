package dev.exoquests.paper.menu;

import dev.exoquests.paper.ExoQuestsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

/**
 * Makes ExoQuests menus read-only. While a menu is open, every click (top or bottom inventory, shift,
 * number keys, offhand swap, double-click collect, drops) and every drag is cancelled. Only plain left/right
 * clicks on a top-inventory slot trigger the slot's action, and those run on the next tick after the
 * per-player click cooldown.
 */
public final class MenuListener implements Listener {

    private final ExoQuestsPlugin plugin;

    public MenuListener(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
    }

    private static ExoMenu menu(InventoryView view) {
        InventoryHolder holder = view.getTopInventory().getHolder();
        return holder instanceof ExoMenu menu ? menu : null;
    }

    /** Cancel first so other plugins observe the click as cancelled. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickEarly(InventoryClickEvent event) {
        if (menu(event.getView()) != null) {
            event.setCancelled(true);
        }
    }

    /** Re-assert the cancellation after every other plugin, then dispatch. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        ExoMenu menu = menu(event.getView());
        if (menu == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.viewer().equals(player.getUniqueId())) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getView().getTopInventory().getSize()) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        if (!plugin.menus().allowClick(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            // The menu must still be the one the player has open.
            if (player.isOnline() && menu(player.getOpenInventory()) == menu) {
                menu.click(raw);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (menu(event.getView()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDragLate(InventoryDragEvent event) {
        if (menu(event.getView()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (menu(event.getView()) instanceof ConfirmMenu confirm) {
            plugin.menus().onConfirmClosed(event.getPlayer().getUniqueId(), confirm);
        }
    }
}
