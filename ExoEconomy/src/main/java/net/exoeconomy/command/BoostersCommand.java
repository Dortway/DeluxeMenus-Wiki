package net.exoeconomy.command;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.booster.BoosterShopMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /boosters */
public final class BoostersCommand implements TabExecutor {
    private final ExoEconomyPlugin plugin;

    public BoostersCommand(ExoEconomyPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var msg = plugin.settings().messages();
        if (!(sender instanceof Player player)) { msg.send(sender, "player-only"); return true; }
        if (!player.hasPermission("exoeconomy.boosters")) { msg.send(player, "no-permission"); return true; }
        new BoosterShopMenu(plugin, player).open();
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return List.of(); }
}
