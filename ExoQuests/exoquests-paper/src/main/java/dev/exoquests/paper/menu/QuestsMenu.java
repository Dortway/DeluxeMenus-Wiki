package dev.exoquests.paper.menu;

import static dev.exoquests.paper.text.TextService.n;
import static dev.exoquests.paper.text.TextService.p;

import dev.exoquests.core.config.MenuLayouts;
import dev.exoquests.core.quest.QuestCategory;
import dev.exoquests.core.quest.QuestDefinition;
import dev.exoquests.core.quest.QuestPool;
import dev.exoquests.core.quest.QuestProgressService;
import dev.exoquests.core.time.DurationFormat;
import dev.exoquests.paper.ExoQuestsPlugin;
import dev.exoquests.paper.text.TextService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** The /quests menu: three daily quest slots, player info and a shop button. */
public final class QuestsMenu extends ExoMenu {

    private long balance;

    QuestsMenu(ExoQuestsPlugin plugin, Player player, long balance) {
        super(plugin, player);
        this.balance = balance;
        MenuLayouts.QuestsMenu layout = plugin.configs().current().menus().quests();
        create(layout.rows(), plugin.text().parse(layout.title(), p("player", player.getName())));
        render();
    }

    void setBalance(long balance) {
        this.balance = balance;
    }

    @Override
    public void render() {
        Player player = player();
        if (player == null) {
            return;
        }
        clear();
        MenuLayouts.QuestsMenu layout = plugin.configs().current().menus().quests();
        QuestProgressService progress = plugin.progress();
        Optional<QuestProgressService.SessionView> view = progress.view(viewer);
        if (view.isPresent() && view.get().failed()) {
            progress.ensureLoaded(viewer);
        }
        int completed = 0;
        List<Integer> slots = layout.questSlots();
        for (int i = 0; i < slots.size(); i++) {
            QuestProgressService.SlotView slot = null;
            if (view.isPresent() && !view.get().loading()) {
                for (QuestProgressService.SlotView s : view.get().slots()) {
                    if (s.slot() == i) {
                        slot = s;
                    }
                }
            }
            if (slot != null && slot.completed()) {
                completed++;
            }
            set(slots.get(i), questItem(layout.questItem(), slot), null);
        }
        String resetIn = DurationFormat.compact(progress.timeUntilReset());
        TagResolver info = TagResolver.resolver(p("player", player.getName()), n("balance", balance),
                p("completed", completed), p("reset_in", resetIn));
        set(layout.info().slot(), button(layout.info(), player, info), null);
        set(layout.shop().slot(), button(layout.shop(), player, info), () -> {
            plugin.text().play(player, "click");
            plugin.menus().openShop(player, 0);
        });
        set(layout.close().slot(), button(layout.close(), player, info), () -> {
            plugin.text().play(player, "click");
            player.closeInventory();
        });
        fill(layout.filler());
    }

    private ItemStack questItem(MenuLayouts.QuestItem template, QuestProgressService.SlotView slot) {
        TextService text = plugin.text();
        if (slot == null) {
            return displayItem(Material.CLOCK, text.item(template.loadingName()), List.of());
        }
        QuestPool pool = plugin.configs().current().quests();
        Optional<QuestDefinition> definition = pool.get(slot.questId());
        if (definition.isEmpty()) {
            return displayItem(Material.getMaterial(template.unavailableMaterial()),
                    text.item("<muted>" + slot.questId().replace('_', ' ')),
                    List.of(text.item(template.statusUnavailable())));
        }
        QuestDefinition def = definition.get();
        Optional<QuestCategory> category = pool.category(def.category());
        boolean unicode = plugin.configs().current().settings().style().unicode();
        String categoryIcon = category.map(c -> unicode ? c.icon() : c.fallbackIcon()).orElse("");
        TextColor categoryColor = TextColor.fromHexString(category.map(QuestCategory::color).orElse("#FFFFFF"));
        String status = slot.completed() ? template.statusComplete()
                : slot.progress() > 0 ? template.statusInProgress() : template.statusNotStarted();
        int percent = (int) Math.min(100, (long) slot.progress() * 100 / Math.max(1, slot.target()));
        String objective = def.description().replace("{target}", String.format(Locale.ROOT, "%,d", slot.target()));
        TagResolver values = TagResolver.resolver(
                p("quest", def.name()),
                p("category", category.map(QuestCategory::displayName).orElse(def.category())),
                p("category_icon", categoryIcon),
                Placeholder.styling("category_color", categoryColor),
                p("objective", objective),
                n("progress", slot.progress()),
                n("target", slot.target()),
                p("percent", percent),
                Placeholder.component("bar", text.bar(slot.progress(), slot.target())),
                n("reward", slot.reward()));
        // The status line is itself a template, so it gets the same values as the other lines.
        TagResolver placeholders = TagResolver.resolver(values,
                Placeholder.component("status", text.parse(status, values)));
        Material material = Material.getMaterial(slot.completed() && !template.completedMaterial().isEmpty()
                ? template.completedMaterial() : def.icon());
        ItemStack item = displayItem(material, text.item(template.name(), placeholders),
                text.itemLines(template.lore(), placeholders));
        if (slot.completed() && template.glintWhenComplete()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }
        }
        return item;
    }
}
