package dev.exo.dailyspinner.config;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A configurable item: material, amount, MiniMessage name and lore, glint, custom model data and
 * optional enchantments.
 */
public record ItemTemplate(Material material, int amount, String name, List<String> lore, boolean glow,
                           Integer customModelData, boolean hideTooltip, Map<Enchantment, Integer> enchantments) {

    public static final int MAX_LORE_LINES = 40;

    /** Renders a GUI button: all vanilla tooltip extras are hidden. */
    public ItemStack renderGui(TextService text, TagResolver... resolvers) {
        ItemStack stack = render(text, resolvers);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Renders a real item (used for item rewards defined by material). */
    public ItemStack render(TextService text, TagResolver... resolvers) {
        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (name != null) {
            meta.displayName(text.item(name, resolvers));
        }
        if (!lore.isEmpty()) {
            meta.lore(text.lore(lore, resolvers));
        }
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (customModelData != null) {
            applyCustomModelData(meta, customModelData);
        }
        if (hideTooltip) {
            meta.setHideTooltip(true);
        }
        for (Map.Entry<Enchantment, Integer> e : enchantments.entrySet()) {
            if (meta instanceof EnchantmentStorageMeta storage) {
                storage.addStoredEnchant(e.getKey(), e.getValue(), true);
            } else {
                meta.addEnchant(e.getKey(), e.getValue(), true);
            }
        }
        stack.setItemMeta(meta);
        return stack;
    }

    @SuppressWarnings("deprecation")
    private static void applyCustomModelData(ItemMeta meta, int value) {
        // Integer custom model data is the form understood by every supported version.
        meta.setCustomModelData(value);
    }

    /** Parses and validates a material name. Returns null and records an error when invalid. */
    public static Material parseMaterial(String raw, String file, String path, ConfigErrors errors) {
        if (raw == null || raw.isBlank()) {
            errors.error(file, path, "material is missing");
            return null;
        }
        Material material = Material.matchMaterial(raw.trim());
        if (material == null || material.isAir() || !material.isItem()) {
            errors.error(file, path, "'" + raw + "' is not a valid item material");
            return null;
        }
        return material;
    }

    /**
     * Parses a template section. {@code defaults} supplies values for omitted keys (may be null).
     */
    public static ItemTemplate parse(ConfigurationSection section, String file, String path, ConfigErrors errors,
                                     TextService text, ItemTemplate defaults) {
        if (section == null) {
            if (defaults != null) {
                return defaults;
            }
            errors.error(file, path, "section is missing");
            return fallback();
        }
        Material material = defaults != null && !section.contains("material")
                ? defaults.material()
                : parseMaterial(section.getString("material"), file, path + ".material", errors);
        if (material == null) {
            material = Material.STONE;
        }
        int amount = section.getInt("amount", defaults != null ? defaults.amount() : 1);
        if (amount < 1 || amount > 99) {
            errors.error(file, path + ".amount", "display amount must be between 1 and 99");
            amount = 1;
        }
        String name = section.contains("name") ? section.getString("name") : defaults != null ? defaults.name() : null;
        List<String> lore = section.contains("lore") ? section.getStringList("lore") : defaults != null ? defaults.lore() : List.of();
        if (lore.size() > MAX_LORE_LINES) {
            errors.error(file, path + ".lore", "too many lore lines (max " + MAX_LORE_LINES + ")");
            lore = lore.subList(0, MAX_LORE_LINES);
        }
        validateText(text, name, file, path + ".name", errors);
        for (int i = 0; i < lore.size(); i++) {
            validateText(text, lore.get(i), file, path + ".lore[" + i + "]", errors);
        }
        boolean glow = section.getBoolean("glow", defaults != null && defaults.glow());
        Integer cmd = section.contains("custom-model-data") ? Integer.valueOf(section.getInt("custom-model-data"))
                : defaults != null ? defaults.customModelData() : null;
        boolean hide = section.getBoolean("hide-tooltip", defaults != null && defaults.hideTooltip());
        Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
        ConfigurationSection enchSection = section.getConfigurationSection("enchantments");
        if (enchSection != null) {
            for (String key : enchSection.getKeys(false)) {
                Enchantment enchantment = null;
                NamespacedKey nsk = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
                if (nsk != null) {
                    enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(nsk);
                }
                int level = enchSection.getInt(key);
                if (enchantment == null) {
                    errors.error(file, path + ".enchantments." + key, "unknown enchantment");
                } else if (level < 1 || level > 255) {
                    errors.error(file, path + ".enchantments." + key, "level must be 1-255");
                } else {
                    enchants.put(enchantment, level);
                }
            }
        }
        return new ItemTemplate(material, amount, name, List.copyOf(lore), glow, cmd, hide, Map.copyOf(enchants));
    }

    private static void validateText(TextService text, String value, String file, String path, ConfigErrors errors) {
        if (value == null) {
            return;
        }
        String problem = text.validate(value);
        if (problem != null) {
            errors.error(file, path, "invalid MiniMessage: " + problem);
        }
    }

    public static ItemTemplate fallback() {
        return new ItemTemplate(Material.STONE, 1, null, List.of(), false, null, false, Map.of());
    }
}
