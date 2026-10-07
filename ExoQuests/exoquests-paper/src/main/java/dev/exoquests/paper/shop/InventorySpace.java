package dev.exoquests.paper.shop;

import dev.exoquests.core.shop.SpaceSimulator;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Checks whether stacks fit into a player's main inventory (36 storage slots) without modifying it. */
public final class InventorySpace {

    private InventorySpace() {
    }

    public static boolean fits(Player player, List<ItemStack> stacks) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        List<ItemStack> keys = new ArrayList<>();
        List<SpaceSimulator.Slot> slots = new ArrayList<>(storage.length);
        for (ItemStack s : storage) {
            if (s == null || s.isEmpty()) {
                slots.add(new SpaceSimulator.Slot(null, 0, 64));
            } else {
                slots.add(new SpaceSimulator.Slot(key(keys, s), s.getAmount(), s.getMaxStackSize()));
            }
        }
        List<SpaceSimulator.Request> requests = new ArrayList<>(stacks.size());
        for (ItemStack s : stacks) {
            requests.add(new SpaceSimulator.Request(key(keys, s), s.getAmount(), s.getMaxStackSize()));
        }
        return SpaceSimulator.fits(slots, requests);
    }

    /** Groups stacks by {@link ItemStack#isSimilar}, the same rule used when items are added. */
    private static Integer key(List<ItemStack> keys, ItemStack stack) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).isSimilar(stack)) {
                return i;
            }
        }
        keys.add(stack);
        return keys.size() - 1;
    }
}
