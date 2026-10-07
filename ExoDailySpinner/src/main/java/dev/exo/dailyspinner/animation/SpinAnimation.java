package dev.exo.dailyspinner.animation;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.config.SoundSpec;
import dev.exo.dailyspinner.menu.SpinnerMenu;
import dev.exo.dailyspinner.reward.Rarity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Drives the reel for one spin. Runs a single 1-tick task only while the animation is active.
 * The animation is cosmetic: landing calls {@code onLanded}, which asks the spin service to deliver
 * the reward that was already persisted. It never chooses or changes the result.
 */
public final class SpinAnimation implements Runnable {

    private final ExoDailySpinner plugin;
    private final SpinnerMenu menu;
    private final ReelPlan<ItemStack> plan;
    private final ItemStack winner;
    private final Rarity rarity;
    private final Runnable onLanded;
    private final boolean borderCycle;
    private BukkitTask task;
    private int step;
    private int wait;
    private boolean finished;

    public SpinAnimation(ExoDailySpinner plugin, SpinnerMenu menu, ReelPlan<ItemStack> plan, ItemStack winner,
                         Rarity rarity, boolean borderCycle, Runnable onLanded) {
        this.plugin = plugin;
        this.menu = menu;
        this.plan = plan;
        this.winner = winner;
        this.rarity = rarity;
        this.borderCycle = borderCycle;
        this.onLanded = onLanded;
    }

    public void start() {
        menu.setSpinning();
        menu.showReel(plan.window(0));
        wait = plan.delayBefore(0);
        Player player = Bukkit.getPlayer(menu.viewer());
        if (player != null) {
            plugin.sound(player, "spin-start");
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    @Override
    public void run() {
        if (finished) {
            return;
        }
        if (!menu.isViewing()) {
            // The close handler finalises the spin; just stop drawing.
            stop();
            return;
        }
        if (--wait > 0) {
            return;
        }
        step++;
        menu.showReel(plan.window(step));
        if (borderCycle && step % 3 == 0) {
            menu.cycleBorder();
        }
        Player player = Bukkit.getPlayer(menu.viewer());
        if (step >= plan.steps()) {
            stop();
            menu.showLanded(winner, rarity);
            if (player != null) {
                plugin.sound(player, "land");
            }
            onLanded.run();
            return;
        }
        if (player != null) {
            SoundSpec tick = plugin.bundle() == null ? null : plugin.bundle().settings().sound("tick");
            if (tick != null) {
                float progress = (float) step / plan.steps();
                tick.play(player, Math.min(2.0f, tick.pitch() + progress * 0.45f));
            }
        }
        wait = plan.delayBefore(step);
    }

    public void stop() {
        finished = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isFinished() {
        return finished;
    }
}
