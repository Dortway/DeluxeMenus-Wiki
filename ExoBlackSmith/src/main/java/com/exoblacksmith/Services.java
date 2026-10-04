package com.exoblacksmith;

import com.exoblacksmith.config.Registry;
import com.exoblacksmith.craft.CraftingService;
import com.exoblacksmith.craft.RuneService;
import com.exoblacksmith.item.ItemService;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;

/** Shared service handles passed to menus and commands. */
public record Services(Plugin plugin, Supplier<Registry> registry, ItemService items, CraftingService crafting,
                       RuneService runes) {
    public Registry reg() {
        return registry.get();
    }
}
