package dev.exo.dailyspinner.menu;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.config.MenuSettings;
import dev.exo.dailyspinner.storage.PlayerData;
import org.bukkit.entity.Player;

/** The 9-slot hub: spinner button in the centre, rewards preview and pending rewards. */
public final class MainMenu extends Menu {

    private final PlayerData data;

    public MainMenu(ExoDailySpinner plugin, ConfigBundle bundle, PlayerData data) {
        super(plugin, bundle, data.player());
        this.data = data;
    }

    @Override
    public void render() {
        MenuSettings.Main m = bundle.menus().main();
        create(9, bundle.text().parse(m.title()));
        fill(m.filler());
        for (MenuSettings.Decoration decoration : m.decorations()) {
            for (int slot : decoration.slots()) {
                set(slot, decoration.item(), null);
            }
        }
        set(m.previewSlot(), m.previewButton(), (p, t) -> {
            if (!p.hasPermission("exodailyspinner.preview")) {
                deniedSound(p);
                bundle.messages().send(p, "no-permission");
                return;
            }
            clickSound(p);
            plugin.menus().openPreview(p, 0, true);
        }, Placeholders.player(bundle, data));
        set(m.pendingSlot(), data.pendingItems() > 0 ? m.pendingAvailable() : m.pendingEmpty(), (p, t) -> {
            if (data.pendingItems() <= 0) {
                deniedSound(p);
                bundle.messages().send(p, "claim-none");
                return;
            }
            clickSound(p);
            plugin.spins().claim(p, true);
        }, Placeholders.player(bundle, data));
        renderSpinner();
        startRefresh(20L, this::renderSpinner);
    }

    private void renderSpinner() {
        MenuSettings.Main m = bundle.menus().main();
        String state = plugin.spins().stateFor(viewer, data, bundle);
        set(m.spinnerSlot(), m.spinnerStates().get(state), this::clickSpinner, Placeholders.player(bundle, data));
    }

    private void clickSpinner(Player player, org.bukkit.event.inventory.ClickType type) {
        if (!player.hasPermission("exodailyspinner.use")) {
            deniedSound(player);
            bundle.messages().send(player, "no-permission");
            return;
        }
        clickSound(player);
        plugin.menus().openSpinner(player);
    }
}
