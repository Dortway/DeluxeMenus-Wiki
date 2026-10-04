package com.exoblacksmith.craft;

import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.item.ItemService;
import java.util.function.BooleanSupplier;
import org.bukkit.inventory.ItemStack;

/**
 * Real-item matching. Plugin ingredients must authenticate (signature, id, level, not retired).
 * Vanilla ingredients must not be ExoBlackSmith items and, in strict mode, must be completely plain
 * (no custom name, lore, enchantments, damage or other components), so renamed lookalikes never count.
 */
public final class BukkitStackOps implements StackOps<ItemStack, ItemRef> {
    private final ItemService items;
    private final BooleanSupplier strictVanilla;

    public BukkitStackOps(ItemService items, BooleanSupplier strictVanilla) {
        this.items = items;
        this.strictVanilla = strictVanilla;
    }

    @Override
    public boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    @Override
    public int amount(ItemStack stack) {
        return stack.getAmount();
    }

    @Override
    public int maxStack(ItemStack stack) {
        return stack.getMaxStackSize();
    }

    @Override
    public ItemStack withAmount(ItemStack stack, int amount) {
        ItemStack copy = stack.clone();
        copy.setAmount(amount);
        return copy;
    }

    @Override
    public boolean similar(ItemStack a, ItemStack b) {
        return a.isSimilar(b);
    }

    @Override
    public boolean matches(ItemStack stack, ItemRef ref) {
        if (ref.isVanilla()) {
            if (stack.getType() != ref.vanilla() || items.isTagged(stack)) {
                return false;
            }
            return !strictVanilla.getAsBoolean() || stack.isSimilar(ItemStack.of(ref.vanilla()));
        }
        return items.matches(stack, ref);
    }
}
