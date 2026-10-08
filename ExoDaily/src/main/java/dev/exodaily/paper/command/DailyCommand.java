package dev.exodaily.paper.command;

import dev.exodaily.paper.Messenger;
import dev.exodaily.paper.Permissions;
import dev.exodaily.paper.menu.MenuService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** {@code /daily}: opens the rewards menu. */
public final class DailyCommand implements TabExecutor {

    private final MenuService menus;
    private final Messenger messenger;

    public DailyCommand(MenuService menus, Messenger messenger) {
        this.menus = menus;
        this.messenger = messenger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messenger.send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission(Permissions.USE)) {
            messenger.send(player, "no-permission");
            return true;
        }
        menus.openFromCommand(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        return List.of();
    }
}
