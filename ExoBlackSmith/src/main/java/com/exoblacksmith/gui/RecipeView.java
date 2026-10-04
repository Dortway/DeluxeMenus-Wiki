package com.exoblacksmith.gui;

import com.exoblacksmith.Services;
import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.ItemDef;
import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.config.model.RecipeDef;
import com.exoblacksmith.util.Roman;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Shared rendering helpers for recipe previews. */
final class RecipeView {
    private RecipeView() {
    }

    static String name(Registry reg, ItemRef ref) {
        if (ref.isVanilla()) {
            return ref.vanilla().getKey().getKey().replace('_', ' ');
        }
        ItemDef def = reg.item(ref.exoId());
        String base = def == null ? ref.exoId() : Text.plainName(def.name());
        return def != null && def.maxLevel() > 1 ? base + " " + Roman.of(ref.level()) : base;
    }

    static ItemStack baseItem(Services services, ItemRef ref) {
        return ref.isVanilla() ? ItemStack.of(ref.vanilla()) : services.items().create(ref, 1);
    }

    /** Ingredient lines with owned/required counts. */
    static List<Component> ingredientLines(Services services, Player player, RecipeDef recipe) {
        Registry reg = services.reg();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(Text.item(reg, reg.messages.raw("menu-ingredients-header"), Map.of()));
        for (Map.Entry<ItemRef, Integer> e : recipe.totals().entrySet()) {
            int owned = services.crafting().count(player, e.getKey());
            String key = owned >= e.getValue() ? "menu-ingredient-ok" : "menu-ingredient-missing";
            lines.add(Text.item(reg, reg.messages.raw(key), Map.of("amount", Integer.toString(e.getValue()),
                    "item", name(reg, e.getKey()), "owned", Integer.toString(owned))));
        }
        return lines;
    }

    static boolean craftable(Services services, Player player, RecipeDef recipe) {
        return services.crafting().preview(player, recipe).ok();
    }

    static String categoryLabel(Registry reg, com.exoblacksmith.config.model.Category category) {
        String key = "menu-category-" + category.key();
        return reg.messages.has(key) ? reg.messages.raw(key) : category.key().toLowerCase(Locale.ROOT);
    }
}
