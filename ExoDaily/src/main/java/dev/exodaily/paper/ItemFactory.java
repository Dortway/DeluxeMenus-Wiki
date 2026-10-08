package dev.exodaily.paper;

import dev.exodaily.core.config.MenuConfig;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.text.TextStyler;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds reward items from assignment snapshots and menu icons from menu configuration. Every
 * menu item is tagged so it can be recognised (and removed) if it ever leaks out of a menu.
 * Server thread only.
 */
public final class ItemFactory {

    private final NamespacedKey displayKey;

    public ItemFactory(NamespacedKey displayKey) {
        this.displayKey = displayKey;
    }

    // ------------------------------------------------------------------ rewards

    /** The exact stacks to deliver (none for command-only rewards), or empty if the item cannot be built. */
    public Optional<List<ItemStack>> rewardStacks(RewardDefinition reward, TextStyler styler) {
        if (!reward.type().givesItems()) {
            return Optional.of(List.of());
        }
        Optional<ItemStack> base = baseItem(reward, styler);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        ItemStack item = base.get();
        if (reward.isSerialized()) {
            // Serialized items are delivered exactly as saved, including their own amount.
            return Optional.of(List.of(item));
        }
        int max = Math.max(1, item.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = reward.amount();
        while (remaining > 0) {
            int size = Math.min(max, remaining);
            stacks.add(item.asQuantity(size));
            remaining -= size;
        }
        return Optional.of(stacks);
    }

    /** A single display copy of the reward for menus (tagged, never delivered). */
    public Optional<ItemStack> rewardDisplay(RewardDefinition reward, TextStyler styler, Component fallbackName,
                                             List<Component> loreBefore, List<Component> loreAfter, boolean includeRewardLore) {
        Optional<ItemStack> base = baseItem(reward, styler);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        ItemStack item = base.get();
        if (!reward.isSerialized()) {
            item.setAmount(Math.max(1, Math.min(reward.amount(), item.getMaxStackSize())));
        }
        item.editMeta(meta -> {
            if (!meta.hasDisplayName() && !meta.hasItemName()) {
                meta.displayName(fallbackName);
            }
            List<Component> lore = new ArrayList<>(loreBefore);
            if (includeRewardLore && meta.lore() != null) {
                lore.addAll(meta.lore());
            }
            lore.addAll(loreAfter);
            meta.lore(collapseBlankLines(lore));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            tag(meta);
        });
        return Optional.of(item);
    }

    /** Rewards without lore would otherwise leave two blank separator lines next to each other. */
    private static List<Component> collapseBlankLines(List<Component> lines) {
        List<Component> result = new ArrayList<>(lines.size());
        boolean previousBlank = false;
        for (Component line : lines) {
            boolean blank = PlainTextComponentSerializer.plainText().serialize(line).isBlank();
            if (!(blank && previousBlank)) {
                result.add(line);
            }
            previousBlank = blank;
        }
        return result;
    }

    private Optional<ItemStack> baseItem(RewardDefinition reward, TextStyler styler) {
        if (reward.isSerialized()) {
            try {
                ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(reward.serializedItem()));
                return item.isEmpty() ? Optional.empty() : Optional.of(item);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        Material material = Material.matchMaterial(reward.material());
        if (material == null || !material.isItem() || material.isAir()) {
            return Optional.empty();
        }
        ItemStack item = new ItemStack(material);
        boolean[] valid = {true};
        item.editMeta(meta -> {
            if (reward.name() != null) {
                meta.displayName(styler.render(reward.name()));
            }
            if (!reward.lore().isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : reward.lore()) {
                    lore.add(styler.render(line));
                }
                meta.lore(lore);
            }
            for (Map.Entry<String, Integer> entry : reward.enchantments().entrySet()) {
                NamespacedKey key = NamespacedKey.fromString(entry.getKey().toLowerCase(Locale.ROOT));
                Enchantment enchantment = key == null ? null
                        : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
                if (enchantment == null) {
                    valid[0] = false;
                    return;
                }
                meta.addEnchant(enchantment, entry.getValue(), true);
            }
            for (String flag : reward.flags()) {
                try {
                    meta.addItemFlags(ItemFlag.valueOf(flag));
                } catch (IllegalArgumentException e) {
                    valid[0] = false;
                    return;
                }
            }
            if (reward.customModelData() != null) {
                CustomModelDataComponent component = meta.getCustomModelDataComponent();
                component.setFloats(List.of(reward.customModelData()));
                meta.setCustomModelDataComponent(component);
            }
        });
        return valid[0] ? Optional.of(item) : Optional.empty();
    }

    // ------------------------------------------------------------------ icons

    public ItemStack icon(MenuConfig.Variant variant, TextStyler styler, TagResolver resolver,
                          Map<String, List<Component>> expansions, int amount) {
        Material material = Material.matchMaterial(variant.material());
        if (material == null || !material.isItem() || material.isAir()) {
            material = Material.BARRIER;
        }
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));
        item.editMeta(meta -> {
            meta.displayName(styler.render(variant.name(), resolver));
            List<Component> lore = new ArrayList<>();
            for (String line : variant.lore()) {
                List<Component> expansion = expansions.get(line.trim());
                if (expansion != null) {
                    lore.addAll(expansion);
                } else {
                    lore.add(styler.render(line, resolver));
                }
            }
            meta.lore(lore);
            if (variant.glow()) {
                meta.setEnchantmentGlintOverride(true);
            }
            meta.addItemFlags(ItemFlag.values());
            tag(meta);
        });
        return item;
    }

    public ItemStack filler(String materialName) {
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem() || material.isAir()) {
            material = Material.BLACK_STAINED_GLASS_PANE;
        }
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            tag(meta);
        });
        return item;
    }

    // ------------------------------------------------------------------ tagging

    private void tag(ItemMeta meta) {
        meta.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
    }

    public boolean isDisplayItem(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE);
    }
}
