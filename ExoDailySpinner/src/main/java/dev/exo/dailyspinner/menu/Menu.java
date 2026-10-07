package dev.exo.dailyspinner.menu;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.config.ItemTemplate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for every plugin GUI. The menu instance itself is the {@link InventoryHolder}, so
 * listeners identify plugin inventories by holder type rather than by title. Each menu is bound to
 * one viewer and carries a unique session id checked by {@link MenuManager} on every click.
 */
public abstract class Menu implements InventoryHolder {

    private static final AtomicLong SESSIONS = new AtomicLong();

    @FunctionalInterface
    public interface ClickAction {
        void click(Player player, ClickType type);
    }

    protected final ExoDailySpinner plugin;
    protected final ConfigBundle bundle;
    protected final UUID viewer;
    private final long sessionId = SESSIONS.incrementAndGet();
    private final Map<Integer, ClickAction> actions = new HashMap<>();
    private Inventory inventory;
    private BukkitTask refreshTask;
    private boolean disposed;

    protected Menu(ExoDailySpinner plugin, ConfigBundle bundle, UUID viewer) {
        this.plugin = plugin;
        this.bundle = bundle;
        this.viewer = viewer;
    }

    protected final void create(int size, Component title) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    public final long sessionId() {
        return sessionId;
    }

    public final UUID viewer() {
        return viewer;
    }

    public final ConfigBundle bundle() {
        return bundle;
    }

    /** Draws the whole menu. Called once before opening. */
    public abstract void render();

    /** Whether a configuration reload should close this menu (false while a spin is animating). */
    public boolean closeOnReload() {
        return true;
    }

    /** @param disconnect true when the inventory closed because the player left the server */
    protected void onClose(Player player, boolean disconnect) {
    }

    final void handleClick(Player player, int slot, ClickType type) {
        ClickAction action = actions.get(slot);
        if (action != null) {
            action.click(player, type);
        }
    }

    final void handleClose(Player player, boolean disconnect) {
        dispose();
        onClose(player, disconnect);
    }

    final void dispose() {
        disposed = true;
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    public final boolean isDisposed() {
        return disposed;
    }

    /** True while the viewer is online and looking at this exact inventory. */
    public final boolean isViewing() {
        Player player = Bukkit.getPlayer(viewer);
        return !disposed && player != null && player.isOnline()
                && player.getOpenInventory().getTopInventory() == inventory;
    }

    protected final void set(int slot, ItemStack item, ClickAction action) {
        inventory.setItem(slot, item == null ? null : GuiItems.mark(item));
        if (action == null) {
            actions.remove(slot);
        } else {
            actions.put(slot, action);
        }
    }

    protected final void set(int slot, ItemTemplate template, ClickAction action, TagResolver... resolvers) {
        set(slot, template.renderGui(bundle.text(), resolvers), action);
    }

    protected final void fill(ItemTemplate filler) {
        ItemStack item = GuiItems.mark(filler.renderGui(bundle.text()));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, item);
            actions.remove(i);
        }
    }

    /** Starts a per-menu refresh task that runs only while this menu is open. */
    protected final void startRefresh(long periodTicks, Runnable refresh) {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isViewing()) {
                dispose();
                return;
            }
            refresh.run();
        }, periodTicks, periodTicks);
    }

    protected final void clickSound(Player player) {
        plugin.sound(player, "click");
    }

    protected final void deniedSound(Player player) {
        plugin.sound(player, "denied");
    }
}
