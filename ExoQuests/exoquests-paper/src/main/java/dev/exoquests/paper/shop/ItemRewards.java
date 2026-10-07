package dev.exoquests.paper.shop;

import dev.exoquests.core.shop.ItemSpec;
import dev.exoquests.paper.text.TextService;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Builds reward item stacks and encodes them for durable purchase records. Serialized items use Paper's
 * {@link ItemStack#serializeAsBytes()}, which stores every item component (name, lore, enchantments,
 * potion contents, custom model data, persistent data, ...) and is upgraded across Minecraft versions.
 */
public final class ItemRewards {

    private ItemRewards() {
    }

    /** One template stack describing the whole reward (amount may exceed the max stack size). */
    public static ItemStack template(ItemSpec spec, TextService text) {
        if (spec.isSerialized()) {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(spec.serialized()));
        }
        Material material = Material.getMaterial(spec.material());
        if (material == null || !material.isItem()) {
            throw new IllegalStateException("unknown item material " + spec.material());
        }
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (spec.name() != null) {
                meta.displayName(text.item(spec.name()));
            }
            if (!spec.lore().isEmpty()) {
                meta.lore(text.itemLines(spec.lore()));
            }
            Registry<Enchantment> enchantments = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
            for (Map.Entry<String, Integer> e : spec.enchantments().entrySet()) {
                meta.addEnchant(enchantment(enchantments, e.getKey()), e.getValue(), true);
            }
            if (meta instanceof EnchantmentStorageMeta book) {
                for (Map.Entry<String, Integer> e : spec.storedEnchantments().entrySet()) {
                    book.addStoredEnchant(enchantment(enchantments, e.getKey()), e.getValue(), true);
                }
            }
            if (spec.customModelData() != null) {
                meta.setCustomModelData(spec.customModelData());
            }
            if (spec.unbreakable()) {
                meta.setUnbreakable(true);
            }
            for (String flag : spec.itemFlags()) {
                meta.addItemFlags(ItemFlag.valueOf(flag));
            }
            stack.setItemMeta(meta);
        }
        stack.setAmount(Math.min(spec.amount(), stack.getMaxStackSize()));
        return stack;
    }

    private static Enchantment enchantment(Registry<Enchantment> registry, String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        Enchantment e = k == null ? null : registry.get(k);
        if (e == null) {
            throw new IllegalStateException("unknown enchantment " + key);
        }
        return e;
    }

    /** Total item count of a reward. */
    public static int totalAmount(ItemSpec spec, ItemStack template) {
        return spec.isSerialized() ? template.getAmount() : spec.amount();
    }

    /** Splits a reward into stacks no larger than the item's maximum stack size. */
    public static List<ItemStack> stacks(ItemStack template, int total) {
        List<ItemStack> out = new ArrayList<>();
        int max = Math.max(1, template.getMaxStackSize());
        int remaining = total;
        while (remaining > 0) {
            ItemStack s = template.clone();
            int amount = Math.min(max, remaining);
            s.setAmount(amount);
            out.add(s);
            remaining -= amount;
        }
        return out;
    }

    public static List<ItemStack> build(ItemSpec spec, TextService text) {
        ItemStack template = template(spec, text);
        return stacks(template, totalAmount(spec, template));
    }

    /** Encodes stacks as {@code base64;base64;...}. */
    public static String encode(List<ItemStack> stacks) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : stacks) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(Base64.getEncoder().encodeToString(s.serializeAsBytes()));
        }
        return sb.toString();
    }

    public static List<ItemStack> decode(String snapshot) {
        List<ItemStack> out = new ArrayList<>();
        for (String part : snapshot.split(";")) {
            if (!part.isEmpty()) {
                out.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(part)));
            }
        }
        return out;
    }
}
