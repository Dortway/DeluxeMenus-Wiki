package net.exoeconomy.sell;

import net.exoeconomy.api.SellItemFilter;
import net.exoeconomy.api.SellPriceProvider;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;

/**
 * Decides whether one stack can be sold and at what unit price.
 *
 * <ol>
 *   <li>Plugin filters ({@link SellItemFilter}) may veto anything: quest, bound or special items.</li>
 *   <li>Plugin price providers ({@link SellPriceProvider}) may price their own custom items.</li>
 *   <li>Everything else must be a plain vanilla item: {@code item.isSimilar(new ItemStack(type))}.
 *       Any custom name, lore, enchantment, persistent data (every plugin tag), custom model data,
 *       damage, stored contents (filled shulker boxes, bundles, chests with NBT) or other component
 *       makes it differ from the default item and it is rejected, so plugin-owned items keep their identity.</li>
 * </ol>
 */
public final class ItemInspector {
    public record Verdict(boolean sellable, String key, BigDecimal unitPrice, boolean custom, String reasonKey, String detail) {
        static Verdict sell(String key, BigDecimal price, boolean custom) { return new Verdict(true, key, price, custom, null, null); }
        static Verdict reject(String reasonKey, String detail) { return new Verdict(false, null, null, false, reasonKey, detail); }
    }

    private final Map<String, SellItemFilter> filters = new LinkedHashMap<>();
    private final Map<String, SellPriceProvider> providers = new LinkedHashMap<>();
    private final Logger log;

    public ItemInspector(Logger log) { this.log = log; }

    public void addFilter(String owner, SellItemFilter filter) { filters.put(owner + "#" + filters.size(), filter); }
    public void addProvider(String owner, SellPriceProvider provider) { providers.put(owner + "#" + providers.size(), provider); }
    public void removeOwner(String owner) {
        filters.keySet().removeIf(k -> k.startsWith(owner + "#"));
        providers.keySet().removeIf(k -> k.startsWith(owner + "#"));
    }

    public Verdict inspect(Player seller, ItemStack item, PriceCatalogue catalogue) {
        for (Map.Entry<String, SellItemFilter> f : filters.entrySet()) {
            try {
                Optional<String> veto = f.getValue().reject(seller, item.clone());
                if (veto.isPresent()) return Verdict.reject("sell-reject-protected", veto.get());
            } catch (RuntimeException e) {
                log.warning("Sell filter " + f.getKey() + " failed (" + e + "); rejecting the item to be safe");
                return Verdict.reject("sell-reject-protected", "filter error");
            }
        }
        for (Map.Entry<String, SellPriceProvider> p : providers.entrySet()) {
            try {
                Optional<BigDecimal> price = p.getValue().price(seller, item.clone());
                if (price.isPresent()) {
                    BigDecimal v = price.get();
                    if (v.signum() <= 0 || v.compareTo(new BigDecimal("1000000")) > 0) return Verdict.reject("sell-reject-unpriced", "invalid custom price");
                    return Verdict.sell("custom:" + p.getKey().substring(0, p.getKey().indexOf('#')) + ":" + item.getType().name(), v, true);
                }
            } catch (RuntimeException e) {
                log.warning("Sell price provider " + p.getKey() + " failed: " + e);
            }
        }
        Optional<BigDecimal> price = catalogue.price(item.getType());
        if (price.isEmpty()) return Verdict.reject(catalogue.listedButDisabled(item.getType()) ? "sell-reject-disabled" : "sell-reject-unpriced", null);
        if (!item.isSimilar(new ItemStack(item.getType()))) return Verdict.reject("sell-reject-modified", describe(item));
        return Verdict.sell(item.getType().name(), price.get(), false);
    }

    /** Best-effort explanation of why an item is not plain. */
    static String describe(ItemStack item) {
        if (!item.hasItemMeta()) return "custom data";
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return "custom data";
        Set<NamespacedKey> keys = meta.getPersistentDataContainer().getKeys();
        if (!keys.isEmpty()) {
            Set<String> owners = new TreeSet<>();
            for (NamespacedKey k : keys) owners.add(k.getNamespace());
            return "plugin item (" + String.join(", ", owners) + ")";
        }
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()) return "stored contents";
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) return "stored contents";
        if (meta.hasEnchants()) return "enchanted";
        if (meta.hasDisplayName()) return "renamed";
        if (meta.hasLore()) return "has lore";
        if (meta instanceof Damageable d && d.hasDamage()) return "damaged";
        return "custom data";
    }
}
