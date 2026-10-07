package dev.exo.dailyspinner.menu;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.config.MenuSettings;
import dev.exo.dailyspinner.reward.RewardDefinition;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.UUID;

/**
 * Administrator reward management. Left/right click adjusts weight (+/-1, shift for 10), the drop
 * key twice removes a reward, and the "add held item" button copies the main-hand item.
 */
public final class AdminMenu extends PagedMenu {

    private static final long CONFIRM_WINDOW_MS = 5000;
    private static final String PERMISSION = "exodailyspinner.admin.rewards";

    private String pendingRemoval;
    private long pendingRemovalAt;

    public AdminMenu(ExoDailySpinner plugin, ConfigBundle bundle, UUID viewer, int page) {
        super(plugin, bundle, viewer, page);
    }

    @Override
    protected MenuSettings.Paged layout() {
        return bundle.menus().admin();
    }

    @Override
    protected void renderReward(int slot, RewardDefinition reward) {
        set(slot, GuiItems.mark(RewardItems.withLore(bundle, reward.displayItem(), layout().rewardLore(),
                RewardItems.resolver(bundle, reward))), (p, t) -> clickReward(p, t, reward.id()));
    }

    private void clickReward(Player player, ClickType type, String id) {
        if (!player.hasPermission(PERMISSION)) {
            deniedSound(player);
            bundle.messages().send(player, "no-permission");
            return;
        }
        RewardDefinition reward = plugin.bundle() == null ? null : plugin.bundle().rewards().get(id);
        if (reward == null) {
            bundle.messages().send(player, "reward-unknown", Placeholder.unparsed("id", id));
            plugin.menus().openAdmin(player, page);
            return;
        }
        if (type == ClickType.DROP || type == ClickType.CONTROL_DROP) {
            long now = System.currentTimeMillis();
            if (id.equals(pendingRemoval) && now - pendingRemovalAt <= CONFIRM_WINDOW_MS) {
                pendingRemoval = null;
                if (plugin.rewardEditor().remove(player, id)) {
                    plugin.menus().openAdmin(player, page);
                }
            } else {
                pendingRemoval = id;
                pendingRemovalAt = now;
                deniedSound(player);
                bundle.messages().send(player, "admin-confirm-remove", Placeholder.unparsed("id", id));
            }
            return;
        }
        double delta = switch (type) {
            case LEFT -> 1;
            case RIGHT -> -1;
            case SHIFT_LEFT -> 10;
            case SHIFT_RIGHT -> -10;
            default -> 0;
        };
        if (delta == 0) {
            return;
        }
        double target = reward.weight() + delta;
        if (target < 1) {
            deniedSound(player);
            bundle.messages().send(player, "admin-weight-min");
            return;
        }
        if (plugin.rewardEditor().setWeight(player, id, target)) {
            clickSound(player);
            plugin.menus().openAdmin(player, page);
        }
    }

    @Override
    protected void renderExtras() {
        MenuSettings.Paged l = layout();
        set(l.extraSlots().get("add-hand"), l.extraItems().get("add-hand"), (p, t) -> {
            if (!p.hasPermission(PERMISSION)) {
                deniedSound(p);
                bundle.messages().send(p, "no-permission");
                return;
            }
            if (plugin.rewardEditor().addFromHand(p, null, plugin.bundle().settings().adminDefaultWeight())) {
                clickSound(p);
                plugin.menus().openAdmin(p, page);
            } else {
                deniedSound(p);
            }
        }, pageResolver());
        set(l.extraSlots().get("reload"), l.extraItems().get("reload"), (p, t) -> {
            if (!p.hasPermission("exodailyspinner.admin.reload")) {
                deniedSound(p);
                bundle.messages().send(p, "no-permission");
                return;
            }
            clickSound(p);
            p.closeInventory();
            plugin.reload(p);
        }, pageResolver());
    }

    @Override
    protected void openPage(Player player, int page) {
        plugin.menus().openAdmin(player, page);
    }

    @Override
    protected void back(Player player) {
        player.closeInventory();
    }
}
