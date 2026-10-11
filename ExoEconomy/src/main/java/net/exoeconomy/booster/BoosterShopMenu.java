package net.exoeconomy.booster;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.api.BoosterInfo;
import net.exoeconomy.api.BoosterScope;
import net.exoeconomy.config.BoosterSettings;
import net.exoeconomy.config.Messages;
import net.exoeconomy.config.RewardSettings;
import net.exoeconomy.core.Money;
import net.exoeconomy.core.booster.BoosterSpec;
import net.exoeconomy.core.booster.Plan;
import net.exoeconomy.menu.Items;
import net.exoeconomy.menu.Menu;
import net.exoeconomy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.*;

/** /boosters: tiers with price, duration, scope and what buying would do; live multipliers and timers. */
public final class BoosterShopMenu extends Menu {
    private final BoosterSettings settings;
    private final Map<Integer, BoosterSettings.Tier> bySlot = new HashMap<>();

    public BoosterShopMenu(ExoEconomyPlugin plugin, Player viewer) {
        super(plugin, viewer);
        this.settings = plugin.settings().boosters();
        this.inventory = Bukkit.createInventory(this, settings.menu().size(), Text.color(settings.menu().title()));
        for (BoosterSettings.Tier t : settings.tiers().values()) bySlot.put(t.slot(), t);
        render();
    }

    private void render() {
        Messages msg = plugin.settings().messages();
        BoosterSettings.Menu m = settings.menu();
        ItemStack filler = Items.icon(m.filler(), " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        for (BoosterSettings.Tier t : settings.tiers().values()) inventory.setItem(t.slot(), tierIcon(t, msg));
        inventory.setItem(m.infoSlot(), info(msg));
        List<BoosterInfo> mine = plugin.boosters().book().snapshot().stream()
                .filter(b -> b.scope() == BoosterScope.GLOBAL || viewer.getUniqueId().equals(b.owner())).toList();
        inventory.setItem(m.activeSlot(), Items.icon(org.bukkit.Material.CLOCK, msg.raw("menus.boosters.active-name"),
                lines(msg, "menus.boosters.active-lore", "count", String.valueOf(mine.size()))));
        inventory.setItem(m.closeSlot(), Items.icon(org.bukkit.Material.BARRIER, msg.raw("menus.close-name"), List.of()));
    }

    private ItemStack tierIcon(BoosterSettings.Tier t, Messages msg) {
        BoosterSpec s = t.spec();
        Plan plan = plugin.boosters().plan(viewer, s);
        String status = switch (plan.kind()) {
            case ACTIVATE -> msg.raw("menus.boosters.status-activate");
            case EXTEND -> msg.format("menus.boosters.status-extend", "remaining", remainingOf(plan.targetId()));
            case QUEUE -> msg.raw("menus.boosters.status-queue");
            case REJECT -> msg.format("menus.boosters.status-blocked", "reason", msg.raw(plan.reason()).replace("%prefix%", ""));
        };
        List<String> lore = new ArrayList<>();
        for (String d : t.description()) lore.add(d);
        lore.addAll(lines(msg, "menus.boosters.tier-lore",
                "scope", msg.raw("scope-names." + s.scope().name().toLowerCase(Locale.ROOT)),
                "activity", plugin.boosters().activityName(s.activity()),
                "multiplier", Money.plain(s.multiplier()), "duration", Text.duration(s.durationMs()),
                "price", plugin.ledger().format(s.price()), "status", status));
        return Items.icon(t.icon(), t.displayName(), lore);
    }

    private String remainingOf(String boosterId) {
        long now = System.currentTimeMillis();
        return plugin.boosters().book().snapshot().stream().filter(b -> b.id().equals(boosterId)).findFirst()
                .map(b -> Text.duration(b.remaining(now))).orElse("-");
    }

    private ItemStack info(Messages msg) {
        List<String> lore = new ArrayList<>(lines(msg, "menus.boosters.info-lore", "balance",
                plugin.ledger().balance(viewer.getUniqueId()).map(plugin.ledger()::format).orElse("-")));
        Set<String> activities = new LinkedHashSet<>(plugin.rewards().activityIds());
        if (plugin.settings().selling().boostersEnabled()) activities.add(RewardSettings.SELLING);
        for (String a : activities) {
            BigDecimal mult = plugin.boosters().book().multiplier(viewer.getUniqueId(), a);
            lore.add(msg.format("menus.boosters.info-multiplier-line", "activity", plugin.boosters().activityName(a), "multiplier", Money.plain(mult)));
        }
        return Items.icon(org.bukkit.Material.NETHER_STAR, msg.raw("menus.boosters.info-name"), lore);
    }

    static List<String> lines(Messages msg, String key, String... values) {
        List<String> out = new ArrayList<>();
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) map.put(values[i], values[i + 1]);
        for (String line : msg.lines(key)) out.add(Text.replace(line, map));
        return out;
    }

    @Override
    protected void click(InventoryClickEvent e) {
        if (e.getClickedInventory() != inventory || (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT)) return;
        int slot = e.getSlot();
        BoosterSettings.Menu m = settings.menu();
        if (slot == m.closeSlot()) { later(this::close); return; }
        if (slot == m.activeSlot()) { later(() -> new ActiveBoostersMenu(plugin, viewer).open()); return; }
        BoosterSettings.Tier tier = bySlot.get(slot);
        if (tier == null) return;
        String problem = plugin.boosters().precheck(viewer, tier);
        if (problem != null) {
            plugin.settings().messages().send(viewer, problem);
            render();
            return;
        }
        later(() -> new BoosterConfirmMenu(plugin, viewer, tier).open());
    }

    @Override
    protected void tick() { render(); }
}
