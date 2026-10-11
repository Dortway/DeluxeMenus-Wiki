package net.exoeconomy.command;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.sell.SellMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /sell and /sell claim */
public final class SellCommand implements TabExecutor {
    private final ExoEconomyPlugin plugin;

    public SellCommand(ExoEconomyPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var msg = plugin.settings().messages();
        if (!(sender instanceof Player player)) { msg.send(sender, "player-only"); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("claim")) {
            if (!player.hasPermission("exoeconomy.sell.claim")) { msg.send(player, "no-permission"); return true; }
            plugin.sell().deliverClaims(player, false);
            return true;
        }
        if (!player.hasPermission("exoeconomy.sell")) { msg.send(player, "no-permission"); return true; }
        if (!plugin.settings().selling().enabled()) { msg.send(player, "sell-disabled"); return true; }
        if (!plugin.sell().canSell(player)) { msg.send(player, "sell-not-here"); return true; }
        if (!plugin.sellLedger().available()) { msg.send(player, "sell-economy-unavailable"); return true; }
        if (!plugin.database().healthy()) { msg.send(player, "storage-unavailable"); return true; }
        new SellMenu(plugin, player).open();
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 && "claim".startsWith(args[0].toLowerCase()) && sender.hasPermission("exoeconomy.sell.claim") ? List.of("claim") : List.of();
    }
}
