package dev.exodaily.paper.menu;

import org.bukkit.inventory.Inventory;

/** Finds the ExoDaily holder of an inventory, or null if the inventory is not an ExoDaily menu. */
@FunctionalInterface
public interface MenuLookup {

    /** Paper: reads the holder without creating block-state snapshots for other plugins' inventories. */
    MenuLookup PAPER = inventory -> inventory != null && inventory.getHolder(false) instanceof ExoMenu menu ? menu : null;

    ExoMenu find(Inventory inventory);
}
