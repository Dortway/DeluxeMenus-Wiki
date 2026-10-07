package dev.exoquests.paper.command;

import static dev.exoquests.paper.text.TextService.n;
import static dev.exoquests.paper.text.TextService.p;

import dev.exoquests.paper.ExoQuestsPlugin;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /quests}, {@code /quests points [player]}, {@code /quests help}. */
public final class QuestsCommand implements CommandExecutor, TabCompleter {

    private final ExoQuestsPlugin plugin;

    public QuestsCommand(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.text().send(sender, "error.player-only");
                return true;
            }
            plugin.menus().openQuests(player);
            return true;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "help" -> plugin.text().sendList(sender, "help");
            case "points" -> points(sender, args);
            default -> plugin.text().send(sender, "error.usage", p("usage", "/" + label + " [points|help]"));
        }
        return true;
    }

    private void points(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                plugin.text().send(sender, "error.usage", p("usage", "/quests points <player>"));
                return;
            }
            plugin.points().balance(player.getUniqueId()).whenCompleteAsync((balance, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "balance lookup failed", error);
                    plugin.text().send(sender, "error.database");
                } else {
                    plugin.text().send(sender, "points.balance", n("points", balance));
                }
            }, plugin.mainExecutor());
            return;
        }
        if (!sender.hasPermission("exoquests.points.others")) {
            plugin.text().send(sender, "error.no-permission");
            return;
        }
        Optional<PlayerResolver.Target> target = PlayerResolver.resolve(args[1]);
        if (target.isEmpty()) {
            plugin.text().send(sender, "error.unknown-player", p("input", args[1]));
            return;
        }
        plugin.points().balance(target.get().uuid()).whenCompleteAsync((balance, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "balance lookup failed", error);
                plugin.text().send(sender, "error.database");
            } else {
                plugin.text().send(sender, "points.balance-other", p("player", target.get().name()),
                        n("points", balance));
            }
        }, plugin.mainExecutor());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Completions.filter(List.of("points", "help"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("points") && sender.hasPermission("exoquests.points.others")) {
            return Completions.players(args[1]);
        }
        return List.of();
    }
}
