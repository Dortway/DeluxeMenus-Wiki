package dev.exo.dailyspinner.menu;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Tags every item placed in a plugin GUI. Tagged items should never exist outside a menu; if one
 * is ever found in a player inventory (for example through a client or server bug) it is removed.
 */
public final class GuiItems {

    private static NamespacedKey key;

    private GuiItems() {
    }

    public static void init(Plugin plugin) {
        key = new NamespacedKey(plugin, "gui_item");
    }

    public static ItemStack mark(ItemStack item) {
        if (item == null || item.getType().isAir() || key == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && !meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isGuiItem(ItemStack item) {
        if (item == null || key == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Removes any leaked GUI items from the player's inventory and cursor. @return items removed */
    public static int purge(Player player) {
        int removed = 0;
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isGuiItem(contents[i])) {
                inv.setItem(i, null);
                removed++;
            }
        }
        if (isGuiItem(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed++;
        }
        return removed;
    }
}
