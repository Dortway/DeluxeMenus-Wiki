package net.exoeconomy.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

/** Routes inventory events to {@link Menu}s and ticks open menus from one repeating task. */
public final class MenuListener implements Listener {
    private final Set<Menu> open = Collections.newSetFromMap(new IdentityHashMap<>());

    void opened(Menu menu) { open.add(menu); }

    private static Menu menu(org.bukkit.inventory.InventoryView view) {
        return view.getTopInventory().getHolder() instanceof Menu m ? m : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void click(InventoryClickEvent e) {
        Menu m = menu(e.getView());
        if (m == null) return;
        e.setCancelled(true);
        if (m.closed() || !(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(m.viewer.getUniqueId())) return;
        m.click(e);
    }

    /** Re-asserts the menu's decision in case another plugin changed the cancellation. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void clickGuard(InventoryClickEvent e) {
        Menu m = menu(e.getView());
        if (m instanceof GuardedMenu g) e.setCancelled(!g.allowed(e));
        else if (m != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void drag(InventoryDragEvent e) {
        Menu m = menu(e.getView());
        if (m == null) return;
        e.setCancelled(true);
        if (m.closed() || !(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(m.viewer.getUniqueId())) return;
        m.drag(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void dragGuard(InventoryDragEvent e) {
        Menu m = menu(e.getView());
        if (m instanceof GuardedMenu g) e.setCancelled(!g.allowed(e));
        else if (m != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent e) {
        Menu m = menu(e.getView());
        if (m == null) return;
        open.remove(m);
        m.handleClose();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent e) {
        Menu m = menu(e.getPlayer().getOpenInventory());
        if (m != null) {
            open.remove(m);
            m.handleClose();
        }
    }

    public void tick() {
        for (Menu m : new ArrayList<>(open)) {
            if (!m.isOpen()) { open.remove(m); m.handleClose(); continue; }
            m.tick();
        }
    }

    /** Closes every menu (shutdown/reload); sell menus return their items. */
    public void closeAll() {
        for (Menu m : new ArrayList<>(open)) m.close();
        open.clear();
    }

    public List<Menu> openMenus() { return new ArrayList<>(open); }

    /** A menu that decides per event which clicks are allowed (the sell menu). */
    public interface GuardedMenu {
        boolean allowed(InventoryClickEvent e);
        boolean allowed(InventoryDragEvent e);
    }
}
