package dev.exodaily.paper.menu;

import dev.exodaily.core.service.DailyView;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Server-side identity of an ExoDaily inventory. Clicks are authorized only through this holder:
 * which player it belongs to, which view (cycle, day, date) it was rendered from, the session
 * epoch at render time and the slot actions.
 */
public final class ExoMenu implements InventoryHolder {

    private final MenuType type;
    private final UUID viewer;
    private final Component title;
    private Inventory inventory;
    private volatile DailyView view;
    private volatile long epoch;
    private volatile boolean premium;
    private volatile Map<Integer, MenuAction> actions = Map.of();

    public ExoMenu(MenuType type, UUID viewer, Component title) {
        this.type = type;
        this.viewer = viewer;
        this.title = title;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    void apply(DailyView view, long epoch, boolean premium, Map<Integer, MenuAction> actions) {
        this.view = view;
        this.epoch = epoch;
        this.premium = premium;
        this.actions = Map.copyOf(actions);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public MenuType type() {
        return type;
    }

    public UUID viewer() {
        return viewer;
    }

    public Component title() {
        return title;
    }

    public DailyView view() {
        return view;
    }

    public long epoch() {
        return epoch;
    }

    public boolean premium() {
        return premium;
    }

    public MenuAction action(int slot) {
        return actions.get(slot);
    }
}
