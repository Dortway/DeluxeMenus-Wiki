package com.exoblacksmith.gui;

import com.exoblacksmith.Services;
import com.exoblacksmith.config.model.Category;
import com.exoblacksmith.util.Text;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Entry menu: four catalog categories plus the rune forge. */
public final class MainMenu extends Menu {
    public MainMenu(Services services, Player viewer) {
        super(services, viewer);
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected Component title() {
        return Text.trusted(reg(), reg().menus.title("main"), Map.of());
    }

    @Override
    protected void render() {
        category(10, "armor", Category.ARMOR);
        category(12, "masks", Category.MASKS);
        category(14, "runes", Category.RUNES);
        category(16, "upgrades", Category.UPGRADES);
        button(22, configured("runeforge", Map.of()), click -> {
            if (viewer.hasPermission("exoblacksmith.runeforge")) {
                open(new RuneForgeMenu(services, viewer));
            } else {
                Text.send(viewer, reg(), "no-permission");
            }
        });
        button(26, configured("close", Map.of()), click -> close());
        fill();
    }

    private void category(int slot, String button, Category category) {
        int count = reg().recipes(category).size();
        button(slot, configured(button, Map.of("count", Integer.toString(count))),
                click -> open(new CategoryMenu(services, viewer, category, 0)));
    }
}
