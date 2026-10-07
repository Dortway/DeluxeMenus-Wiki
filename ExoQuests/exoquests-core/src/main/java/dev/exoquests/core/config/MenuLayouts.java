package dev.exoquests.core.config;

import java.util.List;

/** Validated GUI layouts from {@code menus.yml}. */
public record MenuLayouts(QuestsMenu quests, ShopMenu shop, ConfirmMenu confirm) {

    public record Button(int slot, String material, String name, List<String> lore) {
    }

    public record Filler(boolean enabled, String material) {
    }

    public record QuestItem(String name, List<String> lore, boolean glintWhenComplete,
                            String completedMaterial, String unavailableMaterial, String statusComplete,
                            String statusInProgress, String statusNotStarted, String statusUnavailable,
                            String loadingName) {
    }

    public record QuestsMenu(String title, int rows, List<Integer> questSlots, QuestItem questItem,
                             Button shop, Button info, Button close, Filler filler) {
    }

    public record ShopMenu(String title, int rows, List<Integer> itemSlots, List<String> entryLore,
                           String statusAffordable, String statusExpensive, String statusLocked, String emptyName,
                           Button previous, Button next, Button back, Button balance, Button close,
                           Filler filler) {
    }

    public record ConfirmMenu(String title, int rows, int previewSlot, Button confirm, Button cancel,
                              Filler filler) {
    }
}
