package dev.exo.dailyspinner.listener;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.menu.GuiItems;
import dev.exo.dailyspinner.menu.Holders;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/** Join/quit/death handling: resume interrupted spins, notify about stored rewards, clean up state. */
public final class PlayerListener implements Listener {

    private final ExoDailySpinner plugin;

    public PlayerListener(ExoDailySpinner plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int removed = GuiItems.purge(player);
        if (removed > 0) {
            plugin.getLogger().warning("Removed " + removed + " leaked menu item(s) from " + player.getName() + " on join");
        }
        scheduleJoinTasks(player);
    }

    /** Resumes reserved spins and reminds the player about pending rewards after a short delay. */
    public void scheduleJoinTasks(Player player) {
        if (plugin.bundle() == null) {
            return;
        }
        UUID id = player.getUniqueId();
        long delay = plugin.bundle().settings().resumeDelayTicks();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || plugin.bundle() == null) {
                return;
            }
            plugin.spins().resume(online);
            plugin.database().submit(repo -> repo.countPending(id)).whenComplete((count, error) -> plugin.sync(() -> {
                Player p = Bukkit.getPlayer(id);
                if (error == null && count != null && count > 0 && p != null && plugin.bundle() != null) {
                    plugin.bundle().messages().send(p, "pending-reminder", Placeholder.unparsed("count", String.valueOf(count)));
                }
            }));
        }, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        plugin.spins().handleQuit(id);
        plugin.menus().manager().forget(id);
        plugin.clickThrottle().forget(id);
        plugin.commandThrottle().forget(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (Holders.menu(player.getOpenInventory().getTopInventory()) != null) {
            player.closeInventory();
        }
    }
}
