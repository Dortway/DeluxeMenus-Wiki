package com.exoblacksmith.config;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/** Titles and button styling from menus.yml. */
public final class MenuSettings {
    public record Button(Material material, String name, List<String> lore) {
    }

    private final Map<String, String> titles;
    private final Map<String, Button> buttons;
    public final Material filler;
    public final Material gridFrame;

    public MenuSettings(Map<String, String> titles, Map<String, Button> buttons, Material filler, Material gridFrame) {
        this.titles = Map.copyOf(titles);
        this.buttons = Map.copyOf(buttons);
        this.filler = filler;
        this.gridFrame = gridFrame;
    }

    public String title(String key) {
        return titles.getOrDefault(key, key);
    }

    public Button button(String key) {
        Button b = buttons.get(key);
        return b != null ? b : new Button(Material.BARRIER, "<red>" + key, List.of());
    }

    static final List<String> REQUIRED_TITLES = List.of("main", "category", "recipe", "runeforge", "runeforge-apply");
    static final List<String> REQUIRED_BUTTONS = List.of("armor", "masks", "runes", "upgrades", "runeforge", "back",
            "previous", "next", "close", "confirm", "confirm-disabled", "info", "arrow", "empty-slot", "rune-slot-empty");
}
