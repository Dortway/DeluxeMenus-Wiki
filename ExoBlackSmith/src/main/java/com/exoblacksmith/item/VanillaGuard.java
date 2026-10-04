package com.exoblacksmith.item;

import com.exoblacksmith.config.Registry;
import com.exoblacksmith.util.Text;
import java.util.function.Supplier;
import org.bukkit.block.Container;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Blocks vanilla transformations that could corrupt or counterfeit ExoBlackSmith items: crafting grids
 * and crafters, anvils, smithing tables (e.g. netherite upgrades changing the base material), grindstones,
 * enchanting tables, and placing head items as blocks (placed heads lose their item data when broken).
 * Each check is individually configurable. Also removes any stray menu icon from player inventories.
 */
public final class VanillaGuard implements Listener {
    private final ItemService items;
    private final Supplier<Registry> registry;

    public VanillaGuard(ItemService items, Supplier<Registry> registry) {
        this.items = items;
        this.registry = registry;
    }

    private boolean anyTagged(ItemStack... stacks) {
        for (ItemStack stack : stacks) {
            if (items.isTagged(stack)) {
                return true;
            }
        }
        return false;
    }

    private void notify(HumanEntity viewer) {
        if (viewer instanceof Player player) {
            Text.actionBar(player, registry.get(), "transformation-blocked", java.util.Map.of());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent event) {
        if (registry.get().settings.blockCrafting && anyTagged(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        if (registry.get().settings.blockCrafting && event.getBlock().getState(false) instanceof Container container
                && anyTagged(container.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        if (registry.get().settings.blockAnvil && anyTagged(event.getInventory().getContents()) && event.getResult() != null) {
            event.setResult(null);
            event.getViewers().forEach(this::notify);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSmithing(PrepareSmithingEvent event) {
        if (registry.get().settings.blockSmithing && anyTagged(event.getInventory().getContents()) && event.getResult() != null) {
            event.setResult(null);
            event.getViewers().forEach(this::notify);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGrindstone(PrepareGrindstoneEvent event) {
        if (registry.get().settings.blockGrindstone && anyTagged(event.getInventory().getContents()) && event.getResult() != null) {
            event.setResult(null);
            event.getViewers().forEach(this::notify);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(PrepareItemEnchantEvent event) {
        if (registry.get().settings.blockEnchanting && items.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (registry.get().settings.blockPlacement && items.isTagged(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        // Menu icons are never real items; if one ever reaches a real inventory, delete it on touch.
        if (items.isMenuIcon(event.getCurrentItem()) && com.exoblacksmith.gui.Holders.menu(event.getView().getTopInventory()) == null) {
            event.setCancelled(true);
            event.setCurrentItem(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ItemStack[] contents = player.getInventory().getContents();
        boolean refresh = registry.get().settings.refreshItemsOnJoin;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (items.isMenuIcon(stack)) {
                player.getInventory().setItem(i, null);
            } else if (refresh && stack != null && items.isTagged(stack) && items.refresh(stack)) {
                player.getInventory().setItem(i, stack);
            }
        }
    }
}
