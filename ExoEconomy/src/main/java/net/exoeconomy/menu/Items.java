package net.exoeconomy.menu;

import net.exoeconomy.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Builds menu icons. Every icon carries a marker tag so it never stacks with real items. */
public final class Items {
    private static NamespacedKey marker;

    private Items() {}

    public static void init(NamespacedKey key) { marker = key; }

    public static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name));
            meta.lore(lore.stream().map(Text::item).toList());
            meta.addItemFlags(ItemFlag.values());
            if (marker != null) meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isIcon(ItemStack item) {
        if (item == null || marker == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }
}
