package dev.exodaily.paper.menu;

import dev.exodaily.paper.ItemFactory;
import dev.exodaily.paper.session.SessionManager;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/**
 * Locks ExoDaily inventories. While an ExoDaily menu is the top inventory, every click (including
 * shift-clicks, number keys, double-clicks, off-hand swaps, drops and creative actions on the
 * lower inventory) and every drag is cancelled. Only plain left/right clicks on the menu itself
 * are interpreted, and only through the server-side {@link ExoMenu} holder.
 */
public final class MenuListener implements Listener {

    private final Plugin plugin;
    private final MenuService menus;
    private final SessionManager sessions;
    private final ItemFactory items;
    private final MenuLookup lookup;

    public MenuListener(Plugin plugin, MenuService menus, SessionManager sessions, ItemFactory items, MenuLookup lookup) {
        this.plugin = plugin;
        this.menus = menus;
        this.sessions = sessions;
        this.items = items;
        this.lookup = lookup;
    }

    private boolean isMenu(Inventory inventory) {
        return lookup.find(inventory) != null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        ExoMenu menu = lookup.find(top);
        if (menu == null) {
            return;
        }
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != top || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        menus.handleClick(player, menu, event.getRawSlot());
    }

    /** Re-asserts cancellation in case another plugin un-cancelled the click. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClickLate(InventoryClickEvent event) {
        if (isMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (isMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDragLate(InventoryDragEvent event) {
        if (isMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isMenu(event.getPlayer().getOpenInventory().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (items.isDisplayItem(event.getItemDrop().getItemStack())) {
            // A menu icon must never exist outside a menu; destroy it instead of dropping it.
            event.getItemDrop().remove();
            plugin.getLogger().warning("Removed a leaked ExoDaily menu item dropped by " + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!isMenu(event.getInventory()) || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        sessions.menuClosed(player.getUniqueId());
        // Defence in depth: after the close settles, remove any menu icon that leaked into the
        // player's inventory (for example through a misbehaving plugin).
        plugin.getServer().getScheduler().runTask(plugin, () -> scrub(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    public void scrub(Player player) {
        if (!player.isOnline()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        boolean removed = false;
        for (int i = 0; i < contents.length; i++) {
            if (items.isDisplayItem(contents[i])) {
                inventory.setItem(i, null);
                removed = true;
            }
        }
        if (items.isDisplayItem(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed = true;
        }
        if (removed) {
            plugin.getLogger().warning("Removed leaked ExoDaily menu items from " + player.getName() + "'s inventory");
        }
    }
}
