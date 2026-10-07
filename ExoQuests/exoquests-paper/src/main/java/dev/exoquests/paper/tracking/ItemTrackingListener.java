package dev.exoquests.paper.tracking;

import dev.exoquests.core.quest.QuestType;
import dev.exoquests.paper.ExoQuestsPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.inventory.ItemStack;

/** Crafting and smelting. */
public final class ItemTrackingListener implements Listener {

    private final TrackingContext ctx;

    public ItemTrackingListener(ExoQuestsPlugin plugin) {
        this.ctx = plugin.tracking();
    }

    /**
     * Counts the items the click will actually produce. Shift-clicks craft as many times as the
     * ingredients and the player's inventory space allow; other clicks craft once if the result can go
     * to the cursor, a hotbar slot, the offhand or be dropped.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !ctx.eligible(player)) {
            return;
        }
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.isEmpty()) {
            return;
        }
        String type = result.getType().name();
        if (!ctx.wants(player, QuestType.CRAFT_ITEM, type)) {
            return;
        }
        int crafts = crafts(event, player, result);
        if (crafts > 0) {
            ctx.record(player, QuestType.CRAFT_ITEM, type, crafts * result.getAmount(), true, ctx.period());
        }
    }

    static int crafts(CraftItemEvent event, Player player, ItemStack result) {
        ClickType click = event.getClick();
        switch (click) {
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                int byIngredients = Integer.MAX_VALUE;
                for (ItemStack ingredient : event.getInventory().getMatrix()) {
                    if (ingredient != null && !ingredient.isEmpty()) {
                        byIngredients = Math.min(byIngredients, ingredient.getAmount());
                    }
                }
                if (byIngredients == Integer.MAX_VALUE) {
                    return 0;
                }
                int space = 0;
                for (ItemStack slot : player.getInventory().getStorageContents()) {
                    if (slot == null || slot.isEmpty()) {
                        space += result.getMaxStackSize();
                    } else if (slot.isSimilar(result)) {
                        space += Math.max(0, slot.getMaxStackSize() - slot.getAmount());
                    }
                }
                return Math.min(byIngredients, space / Math.max(1, result.getAmount()));
            }
            case LEFT, RIGHT -> {
                ItemStack cursor = event.getCursor();
                if (cursor == null || cursor.isEmpty()) {
                    return 1;
                }
                return cursor.isSimilar(result) && cursor.getAmount() + result.getAmount() <= cursor.getMaxStackSize()
                        ? 1 : 0;
            }
            case NUMBER_KEY -> {
                ItemStack target = player.getInventory().getItem(event.getHotbarButton());
                return target == null || target.isEmpty() ? 1 : 0;
            }
            case SWAP_OFFHAND -> {
                ItemStack offhand = player.getInventory().getItemInOffHand();
                return offhand.isEmpty() ? 1 : 0;
            }
            case DROP, CONTROL_DROP -> {
                ItemStack cursor = event.getCursor();
                return cursor == null || cursor.isEmpty() ? 1 : 0;
            }
            default -> {
                return 0;
            }
        }
    }

    /** Only players taking items from the output slot count; hoppers do not fire this event. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onExtract(FurnaceExtractEvent event) {
        Player player = event.getPlayer();
        String type = event.getItemType().name();
        if (event.getItemAmount() > 0 && ctx.eligible(player) && ctx.wants(player, QuestType.SMELT_EXTRACT, type)) {
            ctx.record(player, QuestType.SMELT_EXTRACT, type, event.getItemAmount(), true, ctx.period());
        }
    }
}
