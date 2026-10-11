package net.exoeconomy.sell;

import net.exoeconomy.core.Money;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.*;

/**
 * prices.yml: Shards per single plain vanilla item. Entries are a price, {@code false}, or
 * {@code {price: x, enabled: bool}}. Only materials listed and enabled are sellable.
 */
public final class PriceCatalogue {
    private final Map<Material, BigDecimal> prices;
    private final Set<Material> disabled;
    private final List<Material> missingBlockItems;

    private PriceCatalogue(Map<Material, BigDecimal> prices, Set<Material> disabled, List<Material> missing) {
        this.prices = prices; this.disabled = disabled; this.missingBlockItems = missing;
    }

    public Optional<BigDecimal> price(Material material) { return Optional.ofNullable(prices.get(material)); }
    public boolean listedButDisabled(Material material) { return disabled.contains(material); }
    public Map<Material, BigDecimal> prices() { return prices; }
    public int size() { return prices.size(); }
    public int disabledCount() { return disabled.size(); }
    /** Block items this server has that prices.yml does not mention (treated as not sellable). */
    public List<Material> missingBlockItems() { return missingBlockItems; }

    public static PriceCatalogue load(ConfigurationSection root, List<String> problems, List<String> warnings) {
        Map<Material, BigDecimal> prices = new HashMap<>();
        Set<Material> disabled = new HashSet<>();
        Set<Material> mentioned = new HashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String sectionName : List.of("blocks", "items")) {
            ConfigurationSection section = root.getConfigurationSection(sectionName);
            if (section == null) continue;
            for (String key : section.getKeys(false)) {
                Material m = Material.getMaterial(key.toUpperCase(Locale.ROOT));
                if (m == null || !m.isItem()) { unknown.add(key); continue; }
                mentioned.add(m);
                Object raw = section.get(key);
                String path = "prices.yml " + sectionName + "." + key;
                try {
                    if (raw instanceof Boolean b) {
                        if (b) problems.add(path + ": use a price, not true");
                        else disabled.add(m);
                    } else if (raw instanceof ConfigurationSection c) {
                        BigDecimal p = Money.fromConfig(c.get("price"), path + ".price");
                        if (c.getBoolean("enabled", true) && p.signum() > 0) prices.put(m, p); else disabled.add(m);
                    } else {
                        BigDecimal p = Money.fromConfig(raw, path);
                        if (p.signum() > 0) prices.put(m, p); else disabled.add(m);
                    }
                } catch (IllegalArgumentException e) {
                    problems.add(path + ": " + e.getMessage());
                }
            }
        }
        if (!unknown.isEmpty()) warnings.add("prices.yml lists " + unknown.size() + " material(s) this server does not have (ignored): " + abbreviate(unknown));
        List<Material> missing = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.name().startsWith("LEGACY_") || !m.isBlock() || !m.isItem() || m.isAir()) continue;
            if (!mentioned.contains(m)) missing.add(m);
        }
        if (!missing.isEmpty()) warnings.add("prices.yml has no entry for " + missing.size() + " block item(s) on this server; they are not sellable until priced: "
                + abbreviate(missing.stream().map(Enum::name).toList()));
        return new PriceCatalogue(Collections.unmodifiableMap(prices), Collections.unmodifiableSet(disabled), List.copyOf(missing));
    }

    private static String abbreviate(List<String> names) {
        return names.size() <= 12 ? names.toString() : names.subList(0, 12) + " and " + (names.size() - 12) + " more";
    }
}
