package dev.exo.dailyspinner.menu;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.config.MenuSettings;
import dev.exo.dailyspinner.reward.RewardDefinition;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Read-only, paginated list of every reward with amount, rarity and exact chance. */
public final class PreviewMenu extends PagedMenu {

    private final boolean fromMain;

    public PreviewMenu(ExoDailySpinner plugin, ConfigBundle bundle, UUID viewer, int page, boolean fromMain) {
        super(plugin, bundle, viewer, page);
        this.fromMain = fromMain;
    }

    @Override
    protected MenuSettings.Paged layout() {
        return bundle.menus().preview();
    }

    @Override
    protected void renderReward(int slot, RewardDefinition reward) {
        set(slot, GuiItems.mark(RewardItems.withLore(bundle, reward.displayItem(), layout().rewardLore(),
                RewardItems.resolver(bundle, reward))), null);
    }

    @Override
    protected void openPage(Player player, int page) {
        plugin.menus().openPreview(player, page, fromMain);
    }

    @Override
    protected void back(Player player) {
        if (fromMain) {
            plugin.menus().openMain(player);
        } else {
            player.closeInventory();
        }
    }
}
