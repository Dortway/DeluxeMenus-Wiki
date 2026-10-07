package dev.exoquests.core.config;

import dev.exoquests.core.quest.QuestPool;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses {@code menus.yml} and rejects overlapping or out-of-range slots. */
public final class MenuLayoutsLoader {

    private MenuLayoutsLoader() {
    }

    public static MenuLayouts load(ConfigNode root, PlatformValidator platform) {
        ConfigErrors errors = root.errors();

        ConfigNode q = root.section("quests");
        int qRows = rows(q);
        List<Integer> questSlots = slots(q, "quest-slots", qRows);
        if (questSlots.size() != QuestPool.SLOTS) {
            errors.add(q.child("quest-slots"), "exactly " + QuestPool.SLOTS + " slots are required");
        }
        ConfigNode qi = q.section("quest-item");
        MenuLayouts.QuestItem questItem = new MenuLayouts.QuestItem(qi.requiredString("name"),
                qi.stringList("lore"), qi.bool("glint-when-complete", true),
                material(qi, "completed-material", "", platform, true),
                material(qi, "unavailable-material", "BARRIER", platform, false),
                qi.requiredString("status-complete"), qi.requiredString("status-in-progress"),
                qi.requiredString("status-not-started"), qi.requiredString("status-unavailable"),
                qi.requiredString("loading-name"));
        MenuLayouts.Button qShop = button(q.section("shop-button"), qRows, platform);
        MenuLayouts.Button qInfo = button(q.section("info-button"), qRows, platform);
        MenuLayouts.Button qClose = button(q.section("close-button"), qRows, platform);
        unique(q, merge(questSlots, qShop.slot(), qInfo.slot(), qClose.slot()));
        MenuLayouts.QuestsMenu quests = new MenuLayouts.QuestsMenu(q.requiredString("title"), qRows, questSlots,
                questItem, qShop, qInfo, qClose, filler(q.section("filler"), platform));

        ConfigNode s = root.section("shop");
        int sRows = rows(s);
        List<Integer> itemSlots = slots(s, "item-slots", sRows);
        if (itemSlots.isEmpty()) {
            errors.add(s.child("item-slots"), "at least one slot is required");
        }
        MenuLayouts.Button prev = button(s.section("previous-button"), sRows, platform);
        MenuLayouts.Button next = button(s.section("next-button"), sRows, platform);
        MenuLayouts.Button back = button(s.section("back-button"), sRows, platform);
        MenuLayouts.Button balance = button(s.section("balance-button"), sRows, platform);
        MenuLayouts.Button sClose = button(s.section("close-button"), sRows, platform);
        unique(s, merge(itemSlots, prev.slot(), next.slot(), back.slot(), balance.slot(), sClose.slot()));
        MenuLayouts.ShopMenu shop = new MenuLayouts.ShopMenu(s.requiredString("title"), sRows, itemSlots,
                s.stringList("entry-lore"), s.requiredString("status-affordable"),
                s.requiredString("status-expensive"), s.requiredString("status-locked"),
                s.requiredString("empty-name"), prev, next, back, balance, sClose, filler(s.section("filler"), platform));

        ConfigNode c = root.section("confirm");
        int cRows = rows(c);
        int preview = slot(c, "preview-slot", cRows);
        MenuLayouts.Button confirm = button(c.section("confirm-button"), cRows, platform);
        MenuLayouts.Button cancel = button(c.section("cancel-button"), cRows, platform);
        unique(c, merge(List.of(preview), confirm.slot(), cancel.slot()));
        MenuLayouts.ConfirmMenu confirmMenu = new MenuLayouts.ConfirmMenu(c.requiredString("title"), cRows, preview,
                confirm, cancel, filler(c.section("filler"), platform));

        if (errors.hasProblems()) {
            return null;
        }
        return new MenuLayouts(quests, shop, confirmMenu);
    }

    private static int rows(ConfigNode node) {
        return (int) node.integer("rows", 3, 1, 6);
    }

    private static int slot(ConfigNode node, String key, int rows) {
        return (int) node.requiredInteger(key, 0, rows * 9L - 1);
    }

    private static List<Integer> slots(ConfigNode node, String key, int rows) {
        List<Integer> out = new ArrayList<>();
        for (Long l : node.integerList(key, 0, rows * 9L - 1)) {
            out.add(l.intValue());
        }
        return List.copyOf(out);
    }

    private static List<Integer> merge(List<Integer> base, int... extra) {
        List<Integer> out = new ArrayList<>(base);
        for (int e : extra) {
            out.add(e);
        }
        return out;
    }

    private static void unique(ConfigNode node, List<Integer> slots) {
        Set<Integer> seen = new HashSet<>();
        for (int s : slots) {
            if (!seen.add(s)) {
                node.errors().add(node.path(), "slot " + s + " is used more than once");
            }
        }
    }

    private static MenuLayouts.Button button(ConfigNode node, int rows, PlatformValidator platform) {
        return new MenuLayouts.Button(slot(node, "slot", rows), material(node, "material", "PAPER", platform, false),
                node.requiredString("name"), node.stringList("lore"));
    }

    private static MenuLayouts.Filler filler(ConfigNode node, PlatformValidator platform) {
        return new MenuLayouts.Filler(node.bool("enabled", true),
                material(node, "material", "GRAY_STAINED_GLASS_PANE", platform, false));
    }

    private static String material(ConfigNode node, String key, String fallback, PlatformValidator platform,
                                   boolean allowEmpty) {
        String m = node.string(key, fallback).toUpperCase(Locale.ROOT);
        if (m.isEmpty() && allowEmpty) {
            return m;
        }
        if (!platform.isItemMaterial(m)) {
            node.errors().add(node.child(key), "unknown item material '" + m + "'");
        }
        return m;
    }
}
