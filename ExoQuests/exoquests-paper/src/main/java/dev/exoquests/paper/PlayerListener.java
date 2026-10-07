package dev.exoquests.paper;

import static dev.exoquests.paper.text.TextService.n;
import static dev.exoquests.paper.text.TextService.p;

import dev.exoquests.core.shop.PurchaseResult;
import dev.exoquests.core.storage.PurchaseState;
import dev.exoquests.core.storage.PurchaseStore;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Loads quest state on join, resumes pending deliveries and alerts staff about purchases needing review. */
final class PlayerListener implements Listener {

    private final ExoQuestsPlugin plugin;

    PlayerListener(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        plugin.progress().join(uuid);
        // Give the client a moment to finish joining before delivering items or messages.
        Bukkit.getScheduler().runTaskLater(plugin, () -> resumeDeliveries(uuid), 40L);
        if (player.hasPermission("exoquests.admin.recovery")) {
            plugin.database().submit(c -> PurchaseStore.countByState(c, PurchaseState.NEEDS_REVIEW))
                    .thenAcceptAsync(count -> {
                        if (count > 0 && player.isOnline()) {
                            plugin.text().send(player, "admin.recovery-alert", n("count", count));
                        }
                    }, plugin.mainExecutor());
        }
    }

    private void resumeDeliveries(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        plugin.purchases().resumePending(uuid).thenAcceptAsync(results -> report(uuid, results),
                plugin.mainExecutor());
    }

    private void report(UUID uuid, List<PurchaseResult> results) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        for (PurchaseResult r : results) {
            switch (r.status()) {
                case SUCCESS -> plugin.text().send(player, "shop.pending-delivered",
                        p("item", plugin.shopEditor().plainName(r.itemId())));
                case REFUNDED -> plugin.text().send(player, "shop.pending-refunded", n("balance", Math.max(0, r.balance())));
                case NEEDS_REVIEW -> plugin.text().send(player, "shop.needs-review", p("id", r.purchaseId()));
                default -> plugin.getLogger().log(Level.FINE, "pending delivery " + r.purchaseId() + ": " + r.status());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.progress().quit(uuid);
        plugin.menus().forget(uuid);
    }
}
