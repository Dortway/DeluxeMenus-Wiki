package net.exoeconomy.menu;

import net.exoeconomy.ExoEconomyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Base for ExoEconomy menus. The listener cancels every click and drag by default; subclasses
 * opt in to the moves they allow. A closed menu ignores further events (stale menu protection).
 */
public abstract class Menu implements InventoryHolder {
    protected final ExoEconomyPlugin plugin;
    protected final Player viewer;
    protected Inventory inventory;
    private boolean closed;

    protected Menu(ExoEconomyPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public Player viewer() { return viewer; }

    public boolean closed() { return closed; }

    public void open() {
        plugin.menus().opened(this);
        viewer.openInventory(inventory);
    }

    /** Called with the event already cancelled. */
    protected abstract void click(InventoryClickEvent event);

    protected void drag(InventoryDragEvent event) { }

    /** Called once when the menu closes for any reason (close, disconnect, kick, shutdown). */
    protected void onClose() { }

    /** Called every second while open. */
    protected void tick() { }

    final void handleClose() {
        if (closed) return;
        closed = true;
        onClose();
    }

    /** True if this menu is what the viewer currently has open. */
    public boolean isOpen() {
        return !closed && viewer.isOnline() && viewer.getOpenInventory().getTopInventory().getHolder() == this;
    }

    /** Runs on the next tick; used to open or close inventories, which must not happen inside click events. */
    protected void later(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    public void close() {
        if (isOpen()) viewer.closeInventory();
        handleClose();
    }
}
