package com.exoblacksmith.config.model;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A 3x3 blacksmith recipe. {@code grid} has 9 entries in row-major order; {@code null} = empty.
 * Consumption is by total quantity across the player's inventory; the grid is the presentation.
 */
public record RecipeDef(String id, Category category, ItemRef output, int outputAmount, Ingredient[] grid,
                        String permission, int sortOrder) {

    public RecipeDef {
        if (grid.length != 9) {
            throw new IllegalArgumentException("grid must have 9 cells");
        }
        grid = grid.clone();
    }

    @Override
    public Ingredient[] grid() {
        return grid.clone();
    }

    public Ingredient cell(int index) {
        return grid[index];
    }

    /** Total requirement per ingredient reference, in first-seen order. */
    public Map<ItemRef, Integer> totals() {
        Map<ItemRef, Integer> totals = new LinkedHashMap<>();
        for (Ingredient ingredient : grid) {
            if (ingredient != null) {
                totals.merge(ingredient.ref(), ingredient.amount(), Integer::sum);
            }
        }
        return totals;
    }

    /** Stable content hash used to detect that a recipe changed under an open preview. */
    public String fingerprint() {
        return id + "|" + output.describe() + "x" + outputAmount + "|" + Arrays.toString(Arrays.stream(grid)
                .map(i -> i == null ? "-" : i.ref().describe() + "x" + i.amount()).toArray()) + "|" + permission;
    }

    public List<Ingredient> cells() {
        return Arrays.asList(grid.clone());
    }
}
