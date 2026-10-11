package net.exoeconomy;

import net.exoeconomy.sell.SellMenu;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;

/** Join/quit housekeeping and dependency tracking. */
final class PlayerListener implements Listener {
    private static final String EE_API = "su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI";
    private static final String VAULT_ECONOMY = "net.milkbowl.vault.economy.Economy";
    private final ExoEconomyPlugin plugin;

    PlayerListener(ExoEconomyPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.boosters().join(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) plugin.sell().deliverClaims(p, true); }, 60L);
    }

    /** LOWEST so the final reward deposit happens while ExcellentEconomy still has the player loaded. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent e) {
        plugin.rewards().quit(e.getPlayer());
        plugin.boosters().quit(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void gameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellMenu menu
                && !plugin.settings().selling().gameModes().contains(e.getNewGameMode()))
            Bukkit.getScheduler().runTask(plugin, menu::close);
    }

    @EventHandler
    public void serviceRegistered(ServiceRegisterEvent e) {
        String service = e.getProvider().getService().getName();
        if (service.equals(EE_API)) Bukkit.getScheduler().runTask(plugin, () -> { plugin.ledger().connect(); plugin.reconnectSell(true); });
        if (service.equals(VAULT_ECONOMY)) Bukkit.getScheduler().runTask(plugin, () -> plugin.reconnectSell(false));
    }

    @EventHandler
    public void serviceUnregistered(ServiceUnregisterEvent e) {
        String service = e.getProvider().getService().getName();
        if (service.equals(EE_API)) plugin.ledger().disconnect("ExcellentEconomy unregistered its API");
        if (service.equals(VAULT_ECONOMY) || service.equals(EE_API)) Bukkit.getScheduler().runTask(plugin, () -> plugin.reconnectSell(false));
    }

    @EventHandler
    public void pluginDisabled(PluginDisableEvent e) {
        String name = e.getPlugin().getName();
        plugin.rewards().unregisterActivities(name);
        plugin.sell().inspector().removeOwner(name);
    }
}
