package net.exoeconomy.command;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.reward.NotificationPrefs;
import net.exoeconomy.storage.Database;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /shardnotify [chat|actionbar|sound|all] [on|off]: per-player reward notification switches (saved). */
public final class NotifyCommand implements TabExecutor {
    private final ExoEconomyPlugin plugin;

    public NotifyCommand(ExoEconomyPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var msg = plugin.settings().messages();
        if (!(sender instanceof Player player)) { msg.send(sender, "player-only"); return true; }
        if (!player.hasPermission("exoeconomy.notify")) { msg.send(player, "no-permission"); return true; }
        if (!plugin.settings().rewards().notifications().playerToggle()) { msg.send(player, "notify-locked"); return true; }
        NotificationPrefs prefs = plugin.rewards().prefs();
        String which = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "all";
        int channels = switch (which) {
            case "chat" -> NotificationPrefs.CHAT;
            case "actionbar", "action-bar", "bar" -> NotificationPrefs.ACTION_BAR;
            case "sound" -> NotificationPrefs.SOUND;
            case "all" -> NotificationPrefs.ALL;
            case "status" -> 0;
            default -> -1;
        };
        if (channels < 0) { msg.send(player, "notify-usage", "label", label); return true; }
        if (channels > 0) {
            Boolean on = args.length > 1 ? switch (args[1].toLowerCase(Locale.ROOT)) {
                case "on", "true", "enable" -> Boolean.TRUE;
                case "off", "false", "disable" -> Boolean.FALSE;
                default -> null;
            } : null;
            int muted = prefs.set(player.getUniqueId(), channels, on);
            plugin.database().write(List.of(Database.savePrefs(player.getUniqueId(), muted)));
        }
        var n = plugin.settings().rewards().notifications();
        msg.send(player, "notify-status",
                "chat", state(n.chat(), prefs.enabled(player.getUniqueId(), NotificationPrefs.CHAT)),
                "actionbar", state(n.actionBar(), prefs.enabled(player.getUniqueId(), NotificationPrefs.ACTION_BAR)),
                "sound", state(n.sound(), prefs.enabled(player.getUniqueId(), NotificationPrefs.SOUND)));
        return true;
    }

    private String state(boolean server, boolean player) {
        var msg = plugin.settings().messages();
        return !server ? msg.raw("notify-state-server-off") : player ? msg.raw("notify-state-on") : msg.raw("notify-state-off");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = args.length == 1 ? List.of("all", "chat", "actionbar", "sound", "status")
                : args.length == 2 && !args[0].equalsIgnoreCase("status") ? List.of("on", "off") : List.of();
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(last)).toList();
    }
}
