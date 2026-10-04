package com.exoblacksmith.gui;

import com.exoblacksmith.Services;
import com.exoblacksmith.config.model.Category;
import com.exoblacksmith.config.model.RecipeDef;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Paginated catalog of one category. Icons are previews; clicking opens the recipe view. */
public final class CategoryMenu extends Menu {
    static final int[] CONTENT = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    private final Category category;
    private final int page;

    public CategoryMenu(Services services, Player viewer, Category category, int page) {
        super(services, viewer);
        this.category = category;
        this.page = page;
    }

    @Override
    protected int size() {
        return 54;
    }

    private int pages() {
        return Math.max(1, (reg().recipes(category).size() + CONTENT.length - 1) / CONTENT.length);
    }

    @Override
    protected Component title() {
        return Text.trusted(reg(), reg().menus.title("category"), Map.of(
                "category", RecipeView.categoryLabel(reg(), category),
                "page", Integer.toString(Math.min(page, pages() - 1) + 1), "pages", Integer.toString(pages())));
    }

    @Override
    protected void render() {
        List<RecipeDef> recipes = reg().recipes(category);
        int current = Math.min(page, pages() - 1);
        int start = current * CONTENT.length;
        for (int i = 0; i < CONTENT.length && start + i < recipes.size(); i++) {
            RecipeDef recipe = recipes.get(start + i);
            List<Component> lore = new ArrayList<>(RecipeView.ingredientLines(services, viewer, recipe));
            boolean allowed = viewer.hasPermission(recipe.permission());
            boolean craftable = allowed && RecipeView.craftable(services, viewer, recipe);
            lore.add(Component.empty());
            lore.add(Text.item(reg(), reg().messages.raw(!allowed ? "menu-status-locked"
                    : craftable ? "menu-status-craftable" : "menu-status-missing"), Map.of()));
            lore.add(Text.item(reg(), reg().messages.raw("menu-click-view"), Map.of()));
            ItemStack icon = services.items().icon(RecipeView.baseItem(services, recipe.output()), lore, recipe.outputAmount());
            button(CONTENT[i], icon, click -> open(new RecipeMenu(services, viewer, recipe, current)));
        }
        button(45, configured("back", Map.of()), click -> open(new MainMenu(services, viewer)));
        if (current > 0) {
            button(48, configured("previous", Map.of("page", Integer.toString(current))),
                    click -> open(new CategoryMenu(services, viewer, category, current - 1)));
        }
        button(49, configured("close", Map.of()), click -> close());
        if (current < pages() - 1) {
            button(50, configured("next", Map.of("page", Integer.toString(current + 2))),
                    click -> open(new CategoryMenu(services, viewer, category, current + 1)));
        }
        fill();
    }
}
