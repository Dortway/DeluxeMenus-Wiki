package net.exoeconomy.sell;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.inventory.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

/**
 * Crafting-arbitrage check against the server's real recipe list: flags recipes whose output sells
 * for more than the cheapest sellable combination of inputs. Covers shaped, shapeless, furnace-type
 * and stonecutter recipes whose inputs are material choices. Server thread; takes a few milliseconds.
 */
public final class PriceAudit {
    private PriceAudit() {}

    public record Finding(String recipe, Material output, BigDecimal outputValue, BigDecimal inputValue) {}

    private record Entry(String name, Material output, int count, List<RecipeChoice> inputs) {}

    public static List<Finding> run(PriceCatalogue catalogue) {
        List<Entry> recipes = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe;
            try {
                recipe = it.next();
            } catch (RuntimeException e) {
                continue;
            }
            ItemStack result = recipe.getResult();
            if (result == null || result.getType().isAir()) continue;
            List<RecipeChoice> inputs = inputs(recipe);
            if (inputs == null || inputs.isEmpty()) continue;
            recipes.add(new Entry(recipe instanceof Keyed k ? k.getKey().toString() : recipe.getClass().getSimpleName(),
                    result.getType(), result.getAmount(), inputs));
        }
        // Sellable items are valued at their price. Unsellable intermediates (sticks, nuggets that are
        // not listed, ...) get the cheapest value they can be crafted for, so chains are checked too.
        Map<Material, BigDecimal> values = new HashMap<>();
        values.putAll(catalogue.prices());
        for (int round = 0; round < 32; round++) {
            boolean changed = false;
            for (Entry r : recipes) {
                if (catalogue.price(r.output()).isPresent()) continue;
                Optional<BigDecimal> cost = cost(r, values);
                if (cost.isEmpty()) continue;
                BigDecimal each = cost.get().divide(BigDecimal.valueOf(r.count()), MathContext.DECIMAL64);
                BigDecimal old = values.get(r.output());
                if (old == null || each.compareTo(old) < 0) { values.put(r.output(), each); changed = true; }
            }
            if (!changed) break;
        }
        List<Finding> findings = new ArrayList<>();
        for (Entry r : recipes) {
            Optional<BigDecimal> outPrice = catalogue.price(r.output());
            if (outPrice.isEmpty()) continue;
            Optional<BigDecimal> cost = cost(r, values);
            if (cost.isEmpty()) continue;
            BigDecimal outputValue = outPrice.get().multiply(BigDecimal.valueOf(r.count()), MathContext.DECIMAL64);
            if (outputValue.compareTo(cost.get()) > 0) findings.add(new Finding(r.name(), r.output(), outputValue, cost.get()));
        }
        findings.sort(Comparator.comparing((Finding f) -> f.outputValue().subtract(f.inputValue())).reversed());
        return findings;
    }

    private static Optional<BigDecimal> cost(Entry r, Map<Material, BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (RecipeChoice choice : r.inputs()) {
            Optional<BigDecimal> v = cheapest(choice, values);
            if (v.isEmpty()) return Optional.empty();
            total = total.add(v.get());
        }
        return Optional.of(total);
    }

    private static List<RecipeChoice> inputs(Recipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> map = shaped.getChoiceMap();
            List<RecipeChoice> out = new ArrayList<>();
            for (String row : shaped.getShape())
                for (char c : row.toCharArray()) {
                    RecipeChoice choice = map.get(c);
                    if (choice != null) out.add(choice);
                }
            return out;
        }
        if (recipe instanceof ShapelessRecipe shapeless) return shapeless.getChoiceList();
        if (recipe instanceof CookingRecipe<?> cooking) return List.of(cooking.getInputChoice());
        if (recipe instanceof StonecuttingRecipe cutting) return List.of(cutting.getInputChoice());
        return null;
    }

    /** Cheapest valued material in a choice; empty if none has a value (or the choice is not material-based). */
    private static Optional<BigDecimal> cheapest(RecipeChoice choice, Map<Material, BigDecimal> values) {
        if (!(choice instanceof RecipeChoice.MaterialChoice materials)) return Optional.empty();
        BigDecimal best = null;
        for (Material m : materials.getChoices()) {
            BigDecimal p = values.get(m);
            if (p != null && (best == null || p.compareTo(best) < 0)) best = p;
        }
        return Optional.ofNullable(best);
    }
}
