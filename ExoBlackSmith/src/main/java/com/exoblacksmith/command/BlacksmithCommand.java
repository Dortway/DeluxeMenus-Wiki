package com.exoblacksmith.command;

import com.exoblacksmith.Services;
import com.exoblacksmith.config.model.Category;
import com.exoblacksmith.gui.CategoryMenu;
import com.exoblacksmith.gui.MainMenu;
import com.exoblacksmith.gui.RuneForgeMenu;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /blacksmith [armor|masks|runes|upgrades|runeforge]}. */
public final class BlacksmithCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("armor", "masks", "runes", "upgrades", "runeforge");
    private final Services services;

    public BlacksmithCommand(Services services) {
        this.services = services;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Text.send(sender, services.reg(), "player-only");
            return true;
        }
        if (!player.hasPermission("exoblacksmith.use")) {
            Text.send(player, services.reg(), "no-permission");
            return true;
        }
        if (args.length == 0) {
            new MainMenu(services, player).open();
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("runeforge")) {
            if (!player.hasPermission("exoblacksmith.runeforge")) {
                Text.send(player, services.reg(), "no-permission");
                return true;
            }
            new RuneForgeMenu(services, player).open();
            return true;
        }
        for (Category category : Category.values()) {
            if (category.key().equals(sub)) {
                new CategoryMenu(services, player, category, 0).open();
                return true;
            }
        }
        new MainMenu(services, player).open();
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
