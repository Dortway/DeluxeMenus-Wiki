package dev.exo.dailyspinner.menu;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.config.MenuSettings;
import dev.exo.dailyspinner.reward.Rarity;
import dev.exo.dailyspinner.reward.RewardDefinition;
import dev.exo.dailyspinner.storage.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** The 27-slot crate-style spinner with a horizontal reel and pointers above and below the winner. */
public final class SpinnerMenu extends Menu {

    public enum State { IDLE, RESERVING, SPINNING, LANDED }

    private PlayerData data;
    private State state = State.IDLE;
    private boolean borderAlt;
    private ItemStack borderItem;
    private ItemStack borderAltItem;
    private ItemStack pointerBottomItem;
    private List<ItemStack> reelPool = List.of();

    public SpinnerMenu(ExoDailySpinner plugin, ConfigBundle bundle, PlayerData data) {
        super(plugin, bundle, data.player());
        this.data = data;
    }

    private MenuSettings.Spinner layout() {
        return bundle.menus().spinner();
    }

    @Override
    public void render() {
        MenuSettings.Spinner s = layout();
        create(27, bundle.text().parse(s.title()));
        fill(s.filler());
        borderItem = GuiItems.mark(s.border().renderGui(bundle.text()));
        borderAltItem = GuiItems.mark(s.borderAlt().renderGui(bundle.text()));
        pointerBottomItem = GuiItems.mark(s.pointerBottom().renderGui(bundle.text()));
        drawBorder(borderItem);
        set(s.pointerTopSlot(), s.pointerTop(), null);
        set(s.pointerBottomSlot(), pointerBottomItem, null);
        set(s.backSlot(), s.backButton(), (p, t) -> {
            clickSound(p);
            plugin.menus().openMain(p);
        });
        List<ItemStack> pool = new ArrayList<>();
        for (RewardDefinition reward : bundle.rewards().rewards()) {
            pool.add(RewardItems.reelItem(bundle, reward));
        }
        reelPool = List.copyOf(pool);
        List<ItemStack> idle = new ArrayList<>();
        for (int i = 0; i < s.reelSlots().size(); i++) {
            idle.add(randomReelItem());
        }
        showReel(idle);
        renderInfo();
        renderSpinButton();
        startRefresh(20L, () -> {
            if (state == State.IDLE) {
                renderSpinButton();
                renderInfo();
            }
        });
    }

    /** Cosmetic filler for the reel, weighted like the real odds. Never decides anything. */
    public ItemStack randomReelItem() {
        if (reelPool.isEmpty()) {
            return GuiItems.mark(layout().filler().renderGui(bundle.text()));
        }
        RewardDefinition pick = bundle.rewards().pick(ThreadLocalRandom.current());
        int index = bundle.rewards().rewards().indexOf(pick);
        return reelPool.get(Math.max(0, index));
    }

    public void renderSpinButton() {
        MenuSettings.Spinner s = layout();
        if (state == State.SPINNING || state == State.LANDED) {
            if (s.spinSlot() == s.pointerBottomSlot()) {
                set(s.spinSlot(), pointerBottomItem, null);
            } else {
                set(s.spinSlot(), s.spinStates().get("running"), null, Placeholders.player(bundle, data));
            }
            return;
        }
        String key = state == State.RESERVING ? "reserving" : plugin.spins().stateFor(viewer, data, bundle);
        set(s.spinSlot(), s.spinStates().get(key), this::clickSpin, Placeholders.player(bundle, data));
    }

    private void renderInfo() {
        MenuSettings.Spinner s = layout();
        set(s.infoSlot(), s.info(), null, Placeholders.player(bundle, data));
    }

    private void clickSpin(Player player, ClickType type) {
        if (state != State.IDLE) {
            deniedSound(player);
            return;
        }
        plugin.spins().requestSpin(player, this);
    }

    private void drawBorder(ItemStack item) {
        for (int slot : layout().borderSlots()) {
            getInventory().setItem(slot, item);
        }
    }

    // ------------------------------------------------------------- called by SpinService / animation

    public void setReserving() {
        state = State.RESERVING;
        renderSpinButton();
    }

    public void setSpinning() {
        state = State.SPINNING;
        renderSpinButton();
    }

    /** Returns the menu to idle (e.g. after a failed reservation) with fresh player data. */
    public void setIdle(PlayerData fresh) {
        if (fresh != null) {
            data = fresh;
        }
        state = State.IDLE;
        drawBorder(borderItem);
        renderSpinButton();
        renderInfo();
    }

    public State state() {
        return state;
    }

    public PlayerData data() {
        return data;
    }

    public void showReel(List<ItemStack> items) {
        List<Integer> slots = layout().reelSlots();
        for (int i = 0; i < slots.size() && i < items.size(); i++) {
            getInventory().setItem(slots.get(i), items.get(i));
        }
    }

    public void cycleBorder() {
        borderAlt = !borderAlt;
        drawBorder(borderAlt ? borderAltItem : borderItem);
    }

    public void showLanded(ItemStack winner, Rarity rarity) {
        state = State.LANDED;
        ItemStack pane = new ItemStack(rarity.pane());
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setHideTooltip(true);
            pane.setItemMeta(meta);
        }
        drawBorder(GuiItems.mark(pane));
        getInventory().setItem(layout().winningSlot(), winner);
        renderSpinButton();
    }

    @Override
    public boolean closeOnReload() {
        return state == State.IDLE;
    }

    @Override
    protected void onClose(Player player, boolean disconnect) {
        plugin.spins().spinnerClosed(this, disconnect);
    }
}
