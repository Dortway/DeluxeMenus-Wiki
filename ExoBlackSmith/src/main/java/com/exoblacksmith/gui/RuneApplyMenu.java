package com.exoblacksmith.gui;

import com.exoblacksmith.Services;
import com.exoblacksmith.config.model.RuneDef;
import com.exoblacksmith.craft.RuneService;
import com.exoblacksmith.item.ItemData;
import com.exoblacksmith.item.ItemKind;
import com.exoblacksmith.item.ItemService;
import com.exoblacksmith.item.RuneSlot;
import com.exoblacksmith.util.Roman;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Rune application: shows the chosen armor piece, its three slots, the compatible runes in the player's
 * inventory, a preview of the result and a confirm button. The rune is consumed only by a successful
 * {@link RuneService#apply}, which re-checks everything at that moment.
 */
public final class RuneApplyMenu extends Menu {
    private static final int ARMOR = 11;
    private static final int SELECTED = 13;
    private static final int PREVIEW = 15;
    private static final int[] SLOTS = {20, 21, 22};
    private static final int[] RUNES = {27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44};
    private static final int CONFIRM = 49;

    private record Choice(String runeId, int tier) {
    }

    private final String armorUid;
    private Choice selected;

    public RuneApplyMenu(Services services, Player viewer, String armorUid, Choice selected) {
        super(services, viewer);
        this.armorUid = armorUid;
        this.selected = selected;
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected Component title() {
        return Text.trusted(reg(), reg().menus.title("runeforge-apply"), Map.of());
    }

    @Override
    protected void render() {
        RuneService.ArmorLocation armor = services.runes().findArmor(viewer, armorUid);
        if (armor == null) {
            Text.send(viewer, reg(), "rune-item-missing");
            button(45, configured("back", Map.of()), click -> open(new RuneForgeMenu(services, viewer)));
            fill();
            return;
        }
        set(4, configured("info", Map.of()));
        set(ARMOR, services.items().icon(armor.stack(), List.of(), 1));
        for (int i = 0; i < SLOTS.length; i++) {
            if (i < armor.data().runes().size()) {
                RuneSlot slot = armor.data().runes().get(i);
                RuneDef rune = reg().rune(slot.runeId());
                if (rune != null) {
                    set(SLOTS[i], services.items().icon(services.items().create(rune, slot.tier(), 1), List.of(), 1));
                }
            } else {
                set(SLOTS[i], configured("rune-slot-empty", Map.of("slot", Integer.toString(i + 1))));
            }
        }

        // distinct authentic runes in main inventory
        Map<Choice, Integer> owned = new LinkedHashMap<>();
        for (ItemStack stack : viewer.getInventory().getStorageContents()) {
            ItemService.Identified id = services.items().identify(stack);
            if (id != null && id.data().kind() == ItemKind.RUNE) {
                owned.merge(new Choice(id.data().id(), id.data().level()), stack.getAmount(), Integer::sum);
            }
        }
        if (selected != null && !owned.containsKey(selected)) {
            selected = null;
        }
        int index = 0;
        for (Map.Entry<Choice, Integer> e : owned.entrySet()) {
            if (index >= RUNES.length) {
                break;
            }
            Choice choice = e.getKey();
            RuneDef rune = reg().rune(choice.runeId());
            if (rune == null) {
                continue;
            }
            RuneService.Result check = services.runes().check(armor.data(), armor.def(), rune);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Text.item(reg(), reg().messages.raw("menu-rune-owned"), Map.of("owned", Integer.toString(e.getValue()))));
            lore.add(Text.item(reg(), reg().messages.raw(check == RuneService.Result.OK ? "menu-rune-compatible"
                    : "menu-rune-" + check.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')), Map.of(
                    "slot", rune.slot().display())));
            ItemStack icon = services.items().icon(services.items().create(rune, choice.tier(), 1), lore, e.getValue());
            if (check == RuneService.Result.OK) {
                button(RUNES[index], icon, click -> {
                    selected = choice;
                    refresh();
                });
            } else {
                set(RUNES[index], icon);
            }
            index++;
        }

        if (selected != null) {
            RuneDef rune = reg().rune(selected.runeId());
            set(SELECTED, services.items().icon(services.items().create(rune, selected.tier(), 1), List.of(), 1));
            RuneService.Result check = services.runes().check(armor.data(), armor.def(), rune);
            if (check == RuneService.Result.OK) {
                ItemData result = armor.data().withRune(new RuneSlot(selected.runeId(), selected.tier()));
                set(PREVIEW, services.items().icon(services.items().build(armor.def(), result, 1), List.of(), 1));
                button(CONFIRM, configured("confirm", Map.of("item", Text.plainName(rune.name()) + " " + Roman.of(selected.tier()))),
                        click -> confirm());
            } else {
                selected = null;
            }
        }
        if (selected == null) {
            set(SELECTED, configured("empty-slot", Map.of()));
            set(CONFIRM, configured("confirm-disabled", Map.of("reason", Text.plain(Text.render(reg(), reg().messages.raw("menu-rune-choose"), Map.of())))));
        }
        button(45, configured("back", Map.of()), click -> open(new RuneForgeMenu(services, viewer)));
        button(53, configured("close", Map.of()), click -> close());
        fill();
    }

    private void confirm() {
        if (selected == null) {
            return;
        }
        RuneService.Result result = services.runes().apply(viewer, armorUid, selected.runeId(), selected.tier());
        RuneDef rune = reg().rune(selected.runeId());
        String runeName = rune == null ? selected.runeId() : Text.plainName(rune.name()) + " " + Roman.of(selected.tier());
        switch (result) {
            case OK -> {
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
                Text.send(viewer, reg(), "rune-applied", Map.of("rune", runeName));
                selected = null;
            }
            case INCOMPATIBLE -> Text.send(viewer, reg(), "rune-incompatible", Map.of("rune", runeName,
                    "slot", rune == null ? "" : rune.slot().display()));
            case NO_SLOTS -> Text.send(viewer, reg(), "rune-no-slots");
            case DUPLICATE -> Text.send(viewer, reg(), "rune-duplicate", Map.of("rune", runeName));
            case NOT_ARMOR -> Text.send(viewer, reg(), "rune-not-armor");
            case ARMOR_MISSING, RUNE_MISSING -> Text.send(viewer, reg(), "rune-item-missing");
        }
        refresh();
    }
}
