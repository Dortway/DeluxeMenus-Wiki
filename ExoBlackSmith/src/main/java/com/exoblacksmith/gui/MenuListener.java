package com.exoblacksmith.gui;

import com.exoblacksmith.config.Registry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Locks menus down completely. While a Menu is the top inventory, every click in either inventory is
 * cancelled (shift-click, number keys, offhand swap, double-click collect, drop, creative clone) and
 * every drag is cancelled. Plain left/right clicks on buttons are dispatched on the next tick, after a
 * per-player click cooldown, and only if the same menu is still open.
 */
public final class MenuListener implements Listener {
    private final Plugin plugin;
    private final Supplier<Registry> registry;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public MenuListener(Plugin plugin, Supplier<Registry> registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    private static Menu menuOf(org.bukkit.inventory.Inventory inventory) {
        return Holders.menu(inventory);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Menu menu = menuOf(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return;
        }
        Consumer<ClickType> action = menu.action(event.getRawSlot());
        if (action == null || menu.busy()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < registry.get().settings.clickCooldownMs) {
            return;
        }
        lastClick.put(player.getUniqueId(), now);
        menu.busy(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (player.isOnline() && menuOf(player.getOpenInventory().getTopInventory()) == menu) {
                    action.accept(click);
                }
            } finally {
                menu.busy(false);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu != null) {
            menu.onClose();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    /** Closes every open ExoBlackSmith menu (plugin disable). */
    public static void closeAll() {
        for (Menu menu : Holders.live()) {
            Player viewer = menu.viewer();
            if (viewer.isOnline() && viewer.getOpenInventory() != null
                    && viewer.getOpenInventory().getTopInventory() == menu.getInventory()) {
                viewer.closeInventory();
            }
        }
    }
}
