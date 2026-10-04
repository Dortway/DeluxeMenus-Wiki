package com.exoblacksmith.gui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Resolves ExoBlackSmith menus from inventories. Menus are matched first by identity against the
 * inventories ExoBlackSmith created (weakly referenced), which needs no holder lookup at all; anything
 * else falls back to Paper's snapshot-free {@code getHolder(false)}.
 */
public final class Holders {
    private static final List<WeakReference<Menu>> CREATED = new ArrayList<>();

    private Holders() {
    }

    static synchronized void register(Menu menu) {
        CREATED.removeIf(ref -> ref.get() == null);
        CREATED.add(new WeakReference<>(menu));
    }

    public static Menu menu(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        Menu known = byIdentity(inventory);
        if (known != null) {
            return known;
        }
        InventoryHolder holder = inventory.getHolder(false);
        return holder instanceof Menu menu ? menu : null;
    }

    private static synchronized Menu byIdentity(Inventory inventory) {
        for (Iterator<WeakReference<Menu>> it = CREATED.iterator(); it.hasNext(); ) {
            Menu menu = it.next().get();
            if (menu == null) {
                it.remove();
            } else if (menu.getInventory() == inventory) {
                return menu;
            }
        }
        return null;
    }

    static synchronized List<Menu> live() {
        List<Menu> out = new ArrayList<>();
        for (WeakReference<Menu> ref : CREATED) {
            Menu menu = ref.get();
            if (menu != null) {
                out.add(menu);
            }
        }
        return out;
    }
}
