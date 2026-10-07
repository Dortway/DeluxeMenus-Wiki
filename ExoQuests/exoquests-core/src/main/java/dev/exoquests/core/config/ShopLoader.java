package dev.exoquests.core.config;

import dev.exoquests.core.shop.CommandTemplate;
import dev.exoquests.core.shop.ItemSpec;
import dev.exoquests.core.shop.PriceRules;
import dev.exoquests.core.shop.Revision;
import dev.exoquests.core.shop.RewardType;
import dev.exoquests.core.shop.ShopCatalog;
import dev.exoquests.core.shop.ShopEntry;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses, validates and writes {@code shop.yml}. */
public final class ShopLoader {

    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern PERMISSION = Pattern.compile("[a-z0-9_.-]{1,96}");
    public static final String HEADER = """
            # ExoQuests shop. Prices must be whole numbers from 10 to 1000 quest points.
            # This file is rewritten by /exoquests shop commands (comments other than this header are not kept).
            # Each entry: price, type (item|command), enabled, optional permission, optional display overrides.
            #   item rewards:    item: { material, amount, name, lore, enchantments, stored-enchantments,
            #                            custom-model-data, unbreakable, item-flags }  or  item: { serialized: <base64> }
            #   command rewards: commands: [ ... ] with placeholders {player} {uuid} {item_id} {purchase_id} {price};
            #                    icon and name are required for command rewards.
            """;

    private ShopLoader() {
    }

    public static ShopCatalog load(ConfigNode root, PlatformValidator platform) {
        ConfigErrors errors = root.errors();
        List<ShopEntry> entries = new ArrayList<>();
        for (Map.Entry<String, ConfigNode> e : root.section("items").sections().entrySet()) {
            ShopEntry entry = parse(e.getKey(), e.getValue(), platform);
            if (entry != null) {
                entries.add(entry);
            }
        }
        if (errors.hasProblems()) {
            return null;
        }
        return new ShopCatalog(entries);
    }

    private static ShopEntry parse(String id, ConfigNode n, PlatformValidator platform) {
        ConfigErrors errors = n.errors();
        int before = errors.problems().size();
        if (!ID.matcher(id).matches()) {
            errors.add(n.path(), "shop id must match " + ID.pattern());
        }
        if (!n.has("price")) {
            errors.add(n.child("price"), "is required");
        }
        int price = (int) n.integer("price", PriceRules.MIN, PriceRules.MIN, PriceRules.MAX);
        String typeName = n.string("type", "item").toUpperCase(Locale.ROOT);
        RewardType type = RewardType.ITEM;
        try {
            type = RewardType.valueOf(typeName);
        } catch (IllegalArgumentException ex) {
            errors.add(n.child("type"), "must be 'item' or 'command'");
        }
        String permission = n.string("permission", "");
        if (!permission.isEmpty() && !PERMISSION.matcher(permission).matches()) {
            errors.add(n.child("permission"), "invalid permission node");
        }
        ItemSpec item = null;
        List<String> commands = List.of();
        String icon = n.string("icon", "").toUpperCase(Locale.ROOT);
        String name = n.string("name", "");
        if (type == RewardType.ITEM) {
            if (n.has("commands")) {
                errors.add(n.child("commands"), "only allowed for command rewards");
            }
            item = parseItem(n.section("item"), platform);
        } else {
            commands = new ArrayList<>();
            for (String c : n.stringList("commands")) {
                String problem = CommandTemplate.validate(c);
                if (problem != null) {
                    errors.add(n.child("commands"), problem + " in '" + c + "'");
                } else {
                    commands.add(CommandTemplate.normalize(c));
                }
            }
            if (commands.isEmpty()) {
                errors.add(n.child("commands"), "at least one command is required");
            }
            if (n.has("item")) {
                errors.add(n.child("item"), "only allowed for item rewards");
            }
            if (icon.isEmpty()) {
                errors.add(n.child("icon"), "is required for command rewards");
            }
            if (name.isEmpty()) {
                errors.add(n.child("name"), "is required for command rewards");
            }
        }
        if (!icon.isEmpty() && !platform.isItemMaterial(icon)) {
            errors.add(n.child("icon"), "unknown item material '" + icon + "'");
        }
        List<String> lore = n.stringList("lore");
        boolean enabled = n.bool("enabled", true);
        if (errors.problems().size() != before) {
            return null;
        }
        String revision = Revision.of(type, item, commands);
        return new ShopEntry(id, price, type, item, commands, icon.isEmpty() ? null : icon,
                name.isEmpty() ? null : name, lore, permission.isEmpty() ? null : permission, enabled, revision);
    }

    private static ItemSpec parseItem(ConfigNode n, PlatformValidator platform) {
        ConfigErrors errors = n.errors();
        if (n.keys().isEmpty()) {
            errors.add(n.path(), "is required for item rewards");
            return null;
        }
        if (n.has("serialized")) {
            String data = n.string("serialized", "");
            for (String k : n.keys()) {
                if (!k.equals("serialized")) {
                    errors.add(n.child(k), "cannot be combined with 'serialized'");
                }
            }
            try {
                byte[] bytes = Base64.getDecoder().decode(data);
                if (bytes.length == 0 || bytes.length > 1_048_576) {
                    errors.add(n.child("serialized"), "invalid size");
                }
            } catch (IllegalArgumentException e) {
                errors.add(n.child("serialized"), "is not valid Base64");
            }
            return ItemSpec.serialized(data);
        }
        String material = n.requiredString("material").toUpperCase(Locale.ROOT);
        if (!material.isEmpty() && !platform.isItemMaterial(material)) {
            errors.add(n.child("material"), "unknown item material '" + material + "'");
        }
        int amount = (int) n.integer("amount", 1, 1, ItemSpec.MAX_AMOUNT);
        Map<String, Integer> ench = enchantments(n, "enchantments", platform);
        Map<String, Integer> stored = enchantments(n, "stored-enchantments", platform);
        if (!stored.isEmpty() && !material.equals("ENCHANTED_BOOK")) {
            errors.add(n.child("stored-enchantments"), "only valid for ENCHANTED_BOOK");
        }
        Integer cmd = n.has("custom-model-data")
                ? (int) n.integer("custom-model-data", 0, 0, Integer.MAX_VALUE) : null;
        List<String> flags = new ArrayList<>();
        for (String f : n.stringList("item-flags")) {
            String flag = f.toUpperCase(Locale.ROOT);
            if (!platform.isItemFlag(flag)) {
                errors.add(n.child("item-flags"), "unknown item flag '" + f + "'");
            }
            flags.add(flag);
        }
        String name = n.has("name") ? n.string("name", null) : null;
        return new ItemSpec(null, material, amount, name, n.stringList("lore"), ench, stored, cmd,
                n.bool("unbreakable", false), flags);
    }

    private static Map<String, Integer> enchantments(ConfigNode n, String key, PlatformValidator platform) {
        Map<String, Integer> out = new LinkedHashMap<>();
        ConfigNode section = n.section(key);
        for (String ench : section.keys()) {
            String k = ench.toLowerCase(Locale.ROOT);
            if (!platform.isEnchantment(k)) {
                n.errors().add(section.child(ench), "unknown enchantment");
            }
            out.put(k, (int) section.requiredInteger(ench, 1, 255));
        }
        return out;
    }

    /** Converts a catalog back into the YAML structure read by {@link #load}. */
    public static Map<String, Object> toYaml(ShopCatalog catalog) {
        Map<String, Object> items = new LinkedHashMap<>();
        for (ShopEntry e : catalog.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("price", e.price());
            m.put("type", e.type().name().toLowerCase(Locale.ROOT));
            m.put("enabled", e.enabled());
            if (e.permission() != null) {
                m.put("permission", e.permission());
            }
            if (e.icon() != null) {
                m.put("icon", e.icon());
            }
            if (e.displayName() != null) {
                m.put("name", e.displayName());
            }
            if (!e.displayLore().isEmpty()) {
                m.put("lore", e.displayLore());
            }
            if (e.type() == RewardType.ITEM) {
                m.put("item", itemYaml(e.item()));
            } else {
                m.put("commands", e.commands());
            }
            items.put(e.id(), m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("items", items);
        return root;
    }

    private static Map<String, Object> itemYaml(ItemSpec s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s.isSerialized()) {
            m.put("serialized", s.serialized());
            return m;
        }
        m.put("material", s.material());
        m.put("amount", s.amount());
        if (s.name() != null) {
            m.put("name", s.name());
        }
        if (!s.lore().isEmpty()) {
            m.put("lore", s.lore());
        }
        if (!s.enchantments().isEmpty()) {
            m.put("enchantments", new LinkedHashMap<>(s.enchantments()));
        }
        if (!s.storedEnchantments().isEmpty()) {
            m.put("stored-enchantments", new LinkedHashMap<>(s.storedEnchantments()));
        }
        if (s.customModelData() != null) {
            m.put("custom-model-data", s.customModelData());
        }
        if (s.unbreakable()) {
            m.put("unbreakable", true);
        }
        if (!s.itemFlags().isEmpty()) {
            m.put("item-flags", s.itemFlags());
        }
        return m;
    }
}
