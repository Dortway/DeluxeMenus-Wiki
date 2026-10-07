package dev.exo.dailyspinner.menu;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.config.MenuSettings;
import dev.exo.dailyspinner.reward.RewardDefinition;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;
import java.util.UUID;

/** Shared pagination for the rewards preview and the admin menu. */
abstract class PagedMenu extends Menu {

    protected final int page;
    protected final List<RewardDefinition> rewards;

    PagedMenu(ExoDailySpinner plugin, ConfigBundle bundle, UUID viewer, int page) {
        super(plugin, bundle, viewer);
        this.rewards = bundle.rewards().sortedForDisplay();
        int per = Math.max(1, layout().contentSlots().size());
        int pages = Math.max(1, (rewards.size() + per - 1) / per);
        this.page = Math.max(0, Math.min(page, pages - 1));
    }

    protected abstract MenuSettings.Paged layout();

    protected abstract void renderReward(int slot, RewardDefinition reward);

    protected abstract void openPage(Player player, int page);

    protected abstract void back(Player player);

    protected int pages() {
        int per = Math.max(1, layout().contentSlots().size());
        return Math.max(1, (rewards.size() + per - 1) / per);
    }

    protected TagResolver pageResolver() {
        return TagResolver.resolver(
                Placeholder.unparsed("page", String.valueOf(page + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages())),
                Placeholder.unparsed("rewards", String.valueOf(rewards.size())),
                Placeholder.unparsed("total_weight", RewardItems.formatWeight(bundle.rewards().totalWeight())));
    }

    @Override
    public void render() {
        MenuSettings.Paged l = layout();
        create(l.rows() * 9, bundle.text().parse(l.title(), pageResolver()));
        fill(l.filler());
        for (MenuSettings.Decoration decoration : l.decorations()) {
            for (int slot : decoration.slots()) {
                if (slot < l.rows() * 9) {
                    set(slot, decoration.item(), null);
                }
            }
        }
        List<Integer> slots = l.contentSlots();
        int start = page * slots.size();
        for (int i = 0; i < slots.size(); i++) {
            int index = start + i;
            if (index < rewards.size()) {
                renderReward(slots.get(i), rewards.get(index));
            } else {
                getInventory().setItem(slots.get(i), null);
            }
        }
        if (page > 0) {
            set(l.previousSlot(), l.previous(), (p, t) -> {
                clickSound(p);
                openPage(p, page - 1);
            }, pageResolver());
        }
        if (page < pages() - 1) {
            set(l.nextSlot(), l.next(), (p, t) -> {
                clickSound(p);
                openPage(p, page + 1);
            }, pageResolver());
        }
        set(l.backSlot(), l.back(), (p, t) -> {
            clickSound(p);
            back(p);
        }, pageResolver());
        set(l.infoSlot(), l.info(), null, pageResolver());
        renderExtras();
    }

    protected void renderExtras() {
    }

    protected static boolean isShift(ClickType type) {
        return type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT;
    }
}
