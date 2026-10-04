package com.exoblacksmith.gui;

import com.exoblacksmith.Services;
import com.exoblacksmith.config.MenuSettings;
import com.exoblacksmith.config.Registry;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Base class for ExoBlackSmith menus. Every slot is display-only: {@link MenuListener} cancels all
 * clicks and drags while a Menu is open, then dispatches button actions on the next tick.
 */
public abstract class Menu implements InventoryHolder {
    protected final Services services;
    protected final Player viewer;
    private final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
    private Inventory inventory;
    private boolean busy;

    protected Menu(Services services, Player viewer) {
        this.services = services;
        this.viewer = viewer;
    }

    protected abstract int size();

    protected abstract Component title();

    /** Populates the inventory; called on open and on every refresh. */
    protected abstract void render();

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected Registry reg() {
        return services.reg();
    }

    public Player viewer() {
        return viewer;
    }

    public void open() {
        inventory = Bukkit.createInventory(this, size(), title());
        Holders.register(this);
        refresh();
        viewer.openInventory(inventory);
    }

    public void refresh() {
        actions.clear();
        inventory.clear();
        render();
    }

    /** Opens another menu next tick-safe (called from dispatched actions, which already run next tick). */
    protected void open(Menu next) {
        next.open();
    }

    boolean busy() {
        return busy;
    }

    void busy(boolean value) {
        this.busy = value;
    }

    Consumer<ClickType> action(int slot) {
        return actions.get(slot);
    }

    protected void set(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    protected void button(int slot, ItemStack item, Consumer<ClickType> action) {
        inventory.setItem(slot, item);
        actions.put(slot, action);
    }

    protected void fill() {
        ItemStack filler = decorate(new ItemStack(reg().menus.filler), Component.text(" "), List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    /** Builds a configured button. */
    protected ItemStack configured(String key, Map<String, String> values) {
        MenuSettings.Button b = reg().menus.button(key);
        return decorate(new ItemStack(b.material()), Text.item(reg(), b.name(), values), Text.lines(reg(), b.lore(), values));
    }

    protected ItemStack decorate(ItemStack stack, Component name, List<Component> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(new ArrayList<>(lore));
            meta.setHideTooltip(false);
            services.items().markIcon(meta);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    protected ItemStack plain(Material material, int amount) {
        ItemStack stack = ItemStack.of(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));
        ItemMeta meta = stack.getItemMeta();
        services.items().markIcon(meta);
        stack.setItemMeta(meta);
        return stack;
    }

    protected void close() {
        viewer.closeInventory();
    }

    /** Called when the viewer closes this menu or disconnects. */
    protected void onClose() {
    }
}
