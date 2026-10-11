package net.exoeconomy.command;

import net.exoeconomy.ExoEconomyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Optional read-only balance command (commands.shards.enabled). Reads through ExcellentEconomy;
 * payments, giving and taking stay with ExcellentEconomy's own currency commands.
 */
public final class ShardsCommand extends BukkitCommand {
    private final ExoEconomyPlugin plugin;

    public ShardsCommand(ExoEconomyPlugin plugin, String name, List<String> aliases) {
        super(name, "Show a Shards balance (via ExcellentEconomy)", "/" + name + " [player]", aliases);
        this.plugin = plugin;
        setPermission("exoeconomy.shards");
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        var msg = plugin.settings().messages();
        if (!sender.hasPermission("exoeconomy.shards")) { msg.send(sender, "no-permission"); return true; }
        Player target;
        if (args.length >= 1) {
            if (!sender.hasPermission("exoeconomy.shards.others")) { msg.send(sender, "no-permission"); return true; }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) { msg.send(sender, "player-not-online", "player", args[0]); return true; }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            msg.send(sender, "player-only");
            return true;
        }
        if (!plugin.ledger().available()) { msg.send(sender, "economy-unavailable"); return true; }
        String balance = plugin.ledger().balance(target.getUniqueId()).map(plugin.ledger()::format).orElse("-");
        msg.send(sender, "shards-balance", "player", target.getName(), "balance", balance);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("exoeconomy.shards.others")) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
