package net.exoeconomy.sell;

import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Set;

/**
 * Reads and edits the contents of shulker box items through {@link BlockStateMeta}, which keeps every other
 * part of the item (name, colour, plugin data) intact. Server thread only.
 */
final class Shulkers {
    private Shulkers() {}

    static boolean isShulker(ItemStack item) {
        return item != null && item.getType().name().endsWith("SHULKER_BOX");
    }

    /** A copy of the shulker's 27 slots, or null if the item carries no shulker contents. */
    static ItemStack[] contents(ItemStack item) {
        if (!isShulker(item) || !item.hasItemMeta()) return null;
        if (!(item.getItemMeta() instanceof BlockStateMeta meta) || !meta.hasBlockState()) return null;
        if (!(meta.getBlockState() instanceof ShulkerBox box)) return null;
        ItemStack[] raw = box.getInventory().getContents();
        ItemStack[] copy = new ItemStack[raw.length];
        for (int i = 0; i < raw.length; i++) copy[i] = raw[i] == null || raw[i].getType().isAir() ? null : raw[i].clone();
        return copy;
    }

    static boolean hasContents(ItemStack item) {
        ItemStack[] c = contents(item);
        if (c == null) return false;
        for (ItemStack i : c) if (i != null) return true;
        return false;
    }

    /** A copy of the shulker with the given inner slots emptied (everything else on the item unchanged). */
    static ItemStack without(ItemStack shulker, Set<Integer> slots) {
        ItemStack copy = shulker.clone();
        BlockStateMeta meta = (BlockStateMeta) copy.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        for (int slot : slots) box.getInventory().setItem(slot, null);
        meta.setBlockState(box);
        copy.setItemMeta(meta);
        return copy;
    }

    /**
     * An empty shulker box that is otherwise a plain vanilla item (no name, lore, enchantments, damage or
     * plugin data). It may still carry an empty container record, for example after its contents were
     * sold, so it is not identical to {@code new ItemStack(type)} but is sold at the normal price.
     */
    static boolean plainEmpty(ItemStack item) {
        if (!isShulker(item)) return false;
        if (!item.hasItemMeta()) return true;
        if (hasContents(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return true;
        return !meta.hasDisplayName() && !meta.hasLore() && !meta.hasEnchants()
                && meta.getPersistentDataContainer().getKeys().isEmpty()
                && !(meta instanceof Damageable d && d.hasDamage());
    }
}
