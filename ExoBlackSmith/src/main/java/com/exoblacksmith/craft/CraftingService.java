package com.exoblacksmith.craft;

import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.config.model.RecipeDef;
import com.exoblacksmith.item.ItemData;
import com.exoblacksmith.item.ItemService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Executes blacksmith recipes against the player's main inventory (36 storage slots; armor, offhand and
 * cursor are never touched). Must be called on the main server thread.
 */
public final class CraftingService {

    public record Outcome(CraftPlan.Status status, Map<ItemRef, Integer> missing, ItemStack output) {
        public boolean ok() {
            return status == CraftPlan.Status.OK;
        }
    }

    private final ItemService items;
    private final Supplier<Registry> registry;
    private final Logger audit;
    private final CraftPlanner<ItemStack, ItemRef> planner;

    public CraftingService(ItemService items, Supplier<Registry> registry, Logger audit) {
        this.items = items;
        this.registry = registry;
        this.audit = audit;
        this.planner = new CraftPlanner<>(new BukkitStackOps(items, () -> registry.get().settings.strictVanilla));
    }

    /** Counts how many authentic units of {@code ref} the player holds in main storage. */
    public int count(Player player, ItemRef ref) {
        BukkitStackOps ops = new BukkitStackOps(items, () -> registry.get().settings.strictVanilla);
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && !stack.isEmpty() && ops.matches(stack, ref)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** Dry run used by menus to show craftability. */
    public CraftPlan<ItemStack, ItemRef> preview(Player player, RecipeDef recipe) {
        ItemStack output = items.create(recipe.output(), recipe.outputAmount());
        return planner.plan(Arrays.asList(player.getInventory().getStorageContents()), recipe.totals(),
                splitOutput(output, recipe.outputAmount()));
    }

    public Outcome craft(Player player, RecipeDef recipe) {
        PlayerInventory inventory = player.getInventory();
        ItemStack output = items.create(recipe.output(), recipe.outputAmount());
        CraftPlan<ItemStack, ItemRef> plan = planner.plan(Arrays.asList(inventory.getStorageContents()),
                recipe.totals(), splitOutput(output, recipe.outputAmount()));
        if (!plan.ok()) {
            return new Outcome(plan.status(), plan.missing(), null);
        }
        // Single main-thread write of the whole storage array: consumption and output happen together.
        inventory.setStorageContents(plan.contents().toArray(new ItemStack[0]));
        for (ItemStack consumed : plan.consumed()) {
            ItemData data = items.read(consumed);
            if (data != null && data.kind().unique()) {
                items.retired().retire(data.uid());
            }
        }
        player.updateInventory();
        audit.info("[craft] " + player.getName() + " (" + player.getUniqueId() + ") crafted " + recipe.id()
                + " -> " + recipe.output().describe() + " x" + recipe.outputAmount());
        return new Outcome(CraftPlan.Status.OK, Map.of(), output);
    }

    private static List<ItemStack> splitOutput(ItemStack output, int amount) {
        List<ItemStack> outputs = new ArrayList<>();
        if (output.getMaxStackSize() == 1 && amount > 1) {
            for (int i = 0; i < amount; i++) {
                ItemStack single = output.clone();
                single.setAmount(1);
                outputs.add(single);
            }
        } else {
            ItemStack copy = output.clone();
            copy.setAmount(amount);
            outputs.add(copy);
        }
        return outputs;
    }
}
