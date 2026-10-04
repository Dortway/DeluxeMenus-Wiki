package com.exoblacksmith.gui;

import com.exoblacksmith.Services;
import com.exoblacksmith.config.model.Ingredient;
import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.config.model.RecipeDef;
import com.exoblacksmith.craft.CraftPlan;
import com.exoblacksmith.craft.CraftingService;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Crafting-table style preview: a 3x3 ingredient grid, an output preview and an explicit craft button.
 * Ingredients are taken from the player's inventory on confirm; nothing in this menu can be taken out.
 * The recipe snapshot is compared with the live registry on confirm, so a reload never crafts a stale
 * recipe: the menu re-renders and asks the player to confirm again.
 */
public final class RecipeMenu extends Menu {
    static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int OUTPUT = 25;
    private static final int ARROW = 23;
    private static final int INFO = 4;
    private static final int CONFIRM = 43;

    private RecipeDef recipe;
    private final int returnPage;

    public RecipeMenu(Services services, Player viewer, RecipeDef recipe, int returnPage) {
        super(services, viewer);
        this.recipe = recipe;
        this.returnPage = returnPage;
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected Component title() {
        return Text.trusted(reg(), reg().menus.title("recipe"), Map.of("item", RecipeView.name(reg(), recipe.output())));
    }

    @Override
    protected void render() {
        ItemStack frame = decorate(new ItemStack(reg().menus.gridFrame), Component.text(" "), List.of());
        for (int slot : new int[]{0, 1, 2, 3, 5, 9, 13, 18, 22, 27, 31, 36, 37, 38, 39, 40}) {
            set(slot, frame);
        }
        set(INFO, configured("info", Map.of()));
        for (int i = 0; i < 9; i++) {
            Ingredient ingredient = recipe.cell(i);
            if (ingredient == null) {
                set(GRID[i], configured("empty-slot", Map.of()));
                continue;
            }
            int owned = services.crafting().count(viewer, ingredient.ref());
            int needed = recipe.totals().get(ingredient.ref());
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Text.item(reg(), reg().messages.raw("menu-recipe-required"), Map.of(
                    "amount", Integer.toString(ingredient.amount()), "total", Integer.toString(needed),
                    "owned", Integer.toString(owned))));
            set(GRID[i], services.items().icon(RecipeView.baseItem(services, ingredient.ref()), lore, ingredient.amount()));
        }
        set(ARROW, configured("arrow", Map.of()));
        List<Component> outLore = new ArrayList<>(RecipeView.ingredientLines(services, viewer, recipe));
        set(OUTPUT, services.items().icon(RecipeView.baseItem(services, recipe.output()), outLore, recipe.outputAmount()));

        boolean allowed = viewer.hasPermission(recipe.permission());
        CraftPlan<ItemStack, ItemRef> preview = services.crafting().preview(viewer, recipe);
        if (allowed && preview.ok()) {
            button(CONFIRM, configured("confirm", Map.of("item", RecipeView.name(reg(), recipe.output()))), click -> confirm());
        } else {
            String reason = !allowed ? reg().messages.raw("menu-status-locked")
                    : preview.status() == CraftPlan.Status.NO_SPACE ? reg().messages.raw("menu-status-no-space")
                    : reg().messages.raw("menu-status-missing");
            button(CONFIRM, configured("confirm-disabled", Map.of("reason", Text.plain(Text.render(reg(), reason, Map.of())))),
                    click -> confirm());
        }
        button(45, configured("back", Map.of()), click -> open(new CategoryMenu(services, viewer, recipe.category(), returnPage)));
        button(49, configured("close", Map.of()), click -> close());
        fill();
    }

    private void confirm() {
        RecipeDef live = reg().recipe(recipe.id());
        if (live == null) {
            Text.send(viewer, reg(), "craft-recipe-changed");
            open(new MainMenu(services, viewer));
            return;
        }
        if (!live.fingerprint().equals(recipe.fingerprint())) {
            recipe = live;
            Text.send(viewer, reg(), "craft-recipe-changed");
            refresh();
            return;
        }
        if (!viewer.hasPermission(recipe.permission())) {
            Text.send(viewer, reg(), "craft-no-permission");
            return;
        }
        CraftingService.Outcome outcome = services.crafting().craft(viewer, recipe);
        String item = RecipeView.name(reg(), recipe.output());
        switch (outcome.status()) {
            case OK -> {
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1f);
                Text.send(viewer, reg(), "craft-success", Map.of("item", item, "amount", Integer.toString(recipe.outputAmount())));
            }
            case NO_SPACE -> {
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
                Text.send(viewer, reg(), "craft-no-space", Map.of("item", item));
            }
            case MISSING -> {
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
                List<String> parts = new ArrayList<>();
                outcome.missing().forEach((ref, n) -> parts.add(n + "× " + RecipeView.name(reg(), ref)));
                Text.send(viewer, reg(), "craft-missing", Map.of("item", item, "missing", String.join(", ", parts)));
            }
        }
        refresh();
    }
}
