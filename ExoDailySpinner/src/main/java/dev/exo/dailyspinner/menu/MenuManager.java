package dev.exo.dailyspinner.menu;

import dev.exo.dailyspinner.ExoDailySpinner;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the single plugin menu each player currently has open. A click is only honoured when the
 * clicked inventory's holder is the menu registered for that player and owned by them; anything
 * else (stale menus, foreign viewers) is cancelled and closed. Main thread only.
 */
public final class MenuManager {

    private final ExoDailySpinner plugin;
    private final Map<UUID, Menu> open = new HashMap<>();

    public MenuManager(ExoDailySpinner plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, Menu menu) {
        menu.render();
        open.put(player.getUniqueId(), menu);
        player.openInventory(menu.getInventory());
        if (player.getOpenInventory().getTopInventory() != menu.getInventory()) {
            // Another plugin cancelled the open event.
            open.remove(player.getUniqueId(), menu);
            menu.dispose();
        }
    }

    public Menu current(UUID player) {
        return open.get(player);
    }

    public boolean isCurrent(Player player, Menu menu) {
        return open.get(player.getUniqueId()) == menu && menu.isViewing();
    }

    /** Validates and dispatches a click on a plugin menu. The event is already cancelled. */
    public void click(Player player, Menu menu, int rawSlot, ClickType type) {
        Menu registered = open.get(player.getUniqueId());
        if (registered != menu || !menu.viewer().equals(player.getUniqueId()) || menu.isDisposed()) {
            plugin.debug("Rejected stale/foreign menu click from " + player.getName());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.getOpenInventory().getTopInventory() == menu.getInventory()) {
                    player.closeInventory();
                }
            });
            return;
        }
        Inventory top = menu.getInventory();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }
        if (type != ClickType.LEFT && type != ClickType.RIGHT && type != ClickType.SHIFT_LEFT
                && type != ClickType.SHIFT_RIGHT && type != ClickType.DROP && type != ClickType.CONTROL_DROP) {
            return;
        }
        if (!plugin.clickThrottle().tryPass(player.getUniqueId())) {
            return;
        }
        // Run the action next tick (opening/closing inventories inside a click event is unsafe) and
        // re-check that the same menu session is still the one open.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && isCurrent(player, menu)) {
                menu.handleClick(player, rawSlot, type);
            }
        });
    }

    public void closed(Player player, Menu menu, boolean disconnect) {
        open.remove(player.getUniqueId(), menu);
        menu.handleClose(player, disconnect);
    }

    public void forget(UUID player) {
        Menu menu = open.remove(player);
        if (menu != null) {
            menu.dispose();
        }
    }

    /** Closes menus for a reload; spinning menus stay open and keep their own config generation. */
    public void closeForReload() {
        for (Menu menu : new ArrayList<>(open.values())) {
            if (!menu.closeOnReload()) {
                continue;
            }
            Player player = Bukkit.getPlayer(menu.viewer());
            if (player != null && menu.isViewing()) {
                player.closeInventory();
            }
        }
    }

    public void closeAll() {
        List<Menu> menus = new ArrayList<>(open.values());
        for (Menu menu : menus) {
            Player player = Bukkit.getPlayer(menu.viewer());
            if (player != null && menu.isViewing()) {
                player.closeInventory();
            }
            menu.dispose();
        }
        open.clear();
    }
}
