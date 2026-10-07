package dev.exo.dailyspinner.menu;

import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.reward.Rarity;
import dev.exo.dailyspinner.reward.RewardDefinition;
import dev.exo.dailyspinner.reward.RewardSnapshot;
import dev.exo.dailyspinner.reward.RewardType;
import dev.exo.dailyspinner.util.TimeFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds reward display items for menus (always copies; never touches real reward items). */
public final class RewardItems {

    private RewardItems() {
    }

    public static TagResolver resolver(ConfigBundle bundle, RewardDefinition reward) {
        return TagResolver.resolver(
                Placeholder.component("reward", reward.displayName()),
                Placeholder.unparsed("id", reward.id()),
                Placeholder.parsed("rarity", reward.rarity().name()),
                Placeholder.unparsed("amount", String.valueOf(reward.amount())),
                Placeholder.unparsed("weight", formatWeight(reward.weight())),
                Placeholder.unparsed("chance", TimeFormat.percent(bundle.rewards().probability(reward.id()))),
                Placeholder.unparsed("total_weight", formatWeight(bundle.rewards().totalWeight())),
                Placeholder.parsed("type", bundle.messages().raw(reward.type() == RewardType.ITEM ? "type-item" : "type-command").get(0)),
                Placeholder.unparsed("commands", String.valueOf(reward.commands().size())));
    }

    public static String formatWeight(double weight) {
        if (weight == Math.rint(weight) && Math.abs(weight) < 1e15) {
            return String.valueOf((long) weight);
        }
        return String.format(Locale.ROOT, "%.4f", weight).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Display item with extra lore lines appended. */
    public static ItemStack withLore(ConfigBundle bundle, ItemStack base, List<String> lines, TagResolver resolver) {
        ItemStack item = base.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.addAll(bundle.text().lore(lines, resolver));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack reelItem(ConfigBundle bundle, RewardDefinition reward) {
        return GuiItems.mark(withLore(bundle, reward.displayItem(), bundle.menus().spinner().reelLore(), resolver(bundle, reward)));
    }

    /** Winner item built from the persisted snapshot, so config edits cannot change what is shown. */
    public static ItemStack winnerItem(ConfigBundle bundle, RewardSnapshot snapshot) {
        ItemStack display;
        try {
            display = snapshot.displayData() == null ? null : ItemStack.deserializeBytes(snapshot.displayData());
        } catch (RuntimeException e) {
            display = null;
        }
        if (display == null || display.getType().isAir()) {
            display = new ItemStack(Material.CHEST);
        }
        Rarity rarity = rarity(bundle, snapshot.rarityId());
        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("reward", snapshot.displayName()),
                Placeholder.parsed("rarity", rarity.name()),
                Placeholder.unparsed("amount", String.valueOf(snapshot.type() == RewardType.ITEM ? snapshot.amount() : 1)));
        ItemStack item = withLore(bundle, display, bundle.menus().spinner().winnerLore(), resolver);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return GuiItems.mark(item);
    }

    public static Rarity rarity(ConfigBundle bundle, String id) {
        Rarity rarity = bundle.rewards().rarities().get(id);
        return rarity != null ? rarity : new Rarity(id, "<gray>" + id, Material.GRAY_STAINED_GLASS_PANE, 0, false, false);
    }
}
