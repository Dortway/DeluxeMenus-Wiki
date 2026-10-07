package dev.exo.dailyspinner.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Holder lookup that avoids block-state snapshots on Paper. */
public final class Holders {

    private static volatile boolean snapshotFlagSupported = true;

    private Holders() {
    }

    public static InventoryHolder of(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        if (snapshotFlagSupported) {
            try {
                return inventory.getHolder(false);
            } catch (UnsupportedOperationException | AbstractMethodError e) {
                snapshotFlagSupported = false;
            } catch (RuntimeException e) {
                // Non-Paper test implementations may throw their own "unimplemented" exception.
                snapshotFlagSupported = false;
            }
        }
        return inventory.getHolder();
    }

    public static Menu menu(Inventory inventory) {
        return of(inventory) instanceof Menu menu ? menu : null;
    }
}
