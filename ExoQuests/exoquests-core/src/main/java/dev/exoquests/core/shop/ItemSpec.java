package dev.exoquests.core.shop;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Item reward definition. Either {@code serialized} (Base64 of Paper's {@code ItemStack#serializeAsBytes},
 * which keeps every item component) or the readable material/meta fields are used, never both.
 */
public record ItemSpec(
        String serialized,
        String material,
        int amount,
        String name,
        List<String> lore,
        Map<String, Integer> enchantments,
        Map<String, Integer> storedEnchantments,
        Integer customModelData,
        boolean unbreakable,
        List<String> itemFlags) {

    public static final int MAX_AMOUNT = 36 * 64;

    public ItemSpec {
        lore = lore == null ? List.of() : List.copyOf(lore);
        enchantments = enchantments == null ? Map.of() : Map.copyOf(new TreeMap<>(enchantments));
        storedEnchantments = storedEnchantments == null ? Map.of() : Map.copyOf(new TreeMap<>(storedEnchantments));
        itemFlags = itemFlags == null ? List.of() : List.copyOf(itemFlags);
    }

    public static ItemSpec serialized(String base64) {
        return new ItemSpec(base64, null, 0, null, null, null, null, null, false, null);
    }

    public static ItemSpec simple(String material, int amount) {
        return new ItemSpec(null, material, amount, null, null, null, null, null, false, null);
    }

    public boolean isSerialized() {
        return serialized != null;
    }

    /** Stable text form used to compute the reward revision. */
    String canonical() {
        if (serialized != null) {
            return "serialized=" + serialized;
        }
        return "material=" + material + "|amount=" + amount + "|name=" + name + "|lore=" + lore
                + "|ench=" + new TreeMap<>(enchantments) + "|stored=" + new TreeMap<>(storedEnchantments)
                + "|cmd=" + customModelData + "|unbreakable=" + unbreakable + "|flags=" + itemFlags;
    }
}
