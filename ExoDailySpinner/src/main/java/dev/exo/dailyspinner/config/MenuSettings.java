package dev.exo.dailyspinner.config;

import java.util.List;
import java.util.Map;

/** Validated layouts from menus.yml. */
public record MenuSettings(Main main, Spinner spinner, Paged preview, Paged admin) {

    public record Decoration(List<Integer> slots, ItemTemplate item) {
    }

    public record Main(String title, ItemTemplate filler, List<Decoration> decorations,
                       int spinnerSlot, Map<String, ItemTemplate> spinnerStates,
                       int previewSlot, ItemTemplate previewButton,
                       int pendingSlot, ItemTemplate pendingAvailable, ItemTemplate pendingEmpty) {
    }

    public record Spinner(String title, ItemTemplate filler, ItemTemplate border, ItemTemplate borderAlt,
                          List<Integer> borderSlots, List<Integer> reelSlots, int winningSlot,
                          int pointerTopSlot, ItemTemplate pointerTop,
                          int pointerBottomSlot, ItemTemplate pointerBottom,
                          int spinSlot, Map<String, ItemTemplate> spinStates,
                          int backSlot, ItemTemplate backButton,
                          int infoSlot, ItemTemplate info,
                          List<String> reelLore, List<String> winnerLore) {

        public int centerIndex() {
            return reelSlots.indexOf(winningSlot);
        }
    }

    /** Shared layout for the paginated preview and admin menus. */
    public record Paged(String title, int rows, ItemTemplate filler, List<Decoration> decorations,
                        List<Integer> contentSlots, int previousSlot, ItemTemplate previous,
                        int nextSlot, ItemTemplate next, int backSlot, ItemTemplate back,
                        int infoSlot, ItemTemplate info, List<String> rewardLore,
                        Map<String, Integer> extraSlots, Map<String, ItemTemplate> extraItems) {
    }
}
