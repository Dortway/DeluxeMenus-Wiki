package dev.exoquests.paper.command;

import dev.exoquests.paper.ExoQuestsPlugin;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /questshop}. */
public final class QuestShopCommand implements CommandExecutor, TabCompleter {

    private final ExoQuestsPlugin plugin;

    public QuestShopCommand(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.text().send(sender, "error.player-only");
            return true;
        }
        plugin.menus().openShop(player, 0);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
