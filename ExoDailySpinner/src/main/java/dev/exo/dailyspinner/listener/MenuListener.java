package dev.exo.dailyspinner.listener;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.menu.GuiItems;
import dev.exo.dailyspinner.menu.Holders;
import dev.exo.dailyspinner.menu.Menu;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;

/**
 * Locks plugin menus down completely. Every click, drag and hotkey interaction while a plugin menu
 * is the top inventory is cancelled (including clicks in the player's own inventory, shift-clicks,
 * number keys, double-click collection, offhand swaps and creative actions). Button actions are
 * dispatched separately after session validation.
 */
public final class MenuListener implements Listener {

    private final ExoDailySpinner plugin;

    public MenuListener(ExoDailySpinner plugin) {
        this.plugin = plugin;
    }

    private static Menu menuOf(Inventory inventory) {
        return Holders.menu(inventory);
    }

    private static Menu openMenu(HumanEntity entity) {
        return menuOf(entity.getOpenInventory().getTopInventory());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickEarly(InventoryClickEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Menu menu = menuOf(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }
        // Re-assert in case another plugin un-cancelled the event.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != menu.getInventory()) {
            return;
        }
        plugin.menus().manager().click(player, menu, event.getRawSlot(), event.getClick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDragEarly(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(InventoryMoveItemEvent event) {
        if (menuOf(event.getSource()) != null || menuOf(event.getDestination()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (openMenu(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (GuiItems.isGuiItem(event.getItemDrop().getItemStack())) {
            event.getItemDrop().remove();
            plugin.getLogger().warning("Removed a leaked menu item dropped by " + event.getPlayer().getName());
            return;
        }
        if (openMenu(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (GuiItems.isGuiItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu == null || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        boolean disconnect = event.getReason() == InventoryCloseEvent.Reason.DISCONNECT;
        plugin.menus().manager().closed(player, menu, disconnect);
        if (!disconnect) {
            int removed = GuiItems.purge(player);
            if (removed > 0) {
                plugin.getLogger().warning("Removed " + removed + " leaked menu item(s) from " + player.getName());
            }
        }
    }
}
