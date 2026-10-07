package dev.exo.dailyspinner.command;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.reward.CommandTemplate;
import dev.exo.dailyspinner.reward.RewardDefinition;
import dev.exo.dailyspinner.reward.RewardEditor;
import dev.exo.dailyspinner.reward.RewardSnapshot;
import dev.exo.dailyspinner.reward.RewardType;
import dev.exo.dailyspinner.menu.RewardItems;
import dev.exo.dailyspinner.storage.ReconcileEntry;
import dev.exo.dailyspinner.storage.SpinRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/** Handles {@code /dailyspinner} (aliases {@code /ds}, {@code /dailyspiner}). */
public final class SpinnerCommand implements TabExecutor {

    private static final String P_USE = "exodailyspinner.use";
    private static final String P_CLAIM = "exodailyspinner.claim";
    private static final String P_PREVIEW = "exodailyspinner.preview";
    private static final String P_ADMIN_MENU = "exodailyspinner.admin.menu";
    private static final String P_REWARDS = "exodailyspinner.admin.rewards";
    private static final String P_REWARDS_COMMAND = "exodailyspinner.admin.rewards.command";
    private static final String P_RESET = "exodailyspinner.admin.reset";
    private static final String P_GIVE = "exodailyspinner.admin.give";
    private static final String P_RELOAD = "exodailyspinner.admin.reload";
    private static final String P_RECONCILE = "exodailyspinner.admin.reconcile";

    private final ExoDailySpinner plugin;

    public SpinnerCommand(ExoDailySpinner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigBundle bundle = plugin.bundle();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (bundle == null) {
            if (sub.equals("reload") && sender.hasPermission(P_RELOAD)) {
                plugin.reload(sender);
            } else {
                sender.sendMessage(Component.text("ExoDailySpinner is not configured correctly. An administrator must fix "
                        + "the errors shown in the console and run /ds reload.", NamedTextColor.RED));
            }
            return true;
        }
        if (sender instanceof Player player && !plugin.commandThrottle().tryPass(player.getUniqueId())) {
            bundle.messages().send(sender, "slow-down");
            return true;
        }
        switch (sub) {
            case "" -> openMain(sender, bundle);
            case "help" -> bundle.messages().send(sender, sender.hasPermission(P_REWARDS) ? "help-admin" : "help");
            case "claim" -> {
                Player player = requirePlayer(sender, bundle);
                if (player != null && require(sender, bundle, P_CLAIM)) {
                    plugin.spins().claim(player, false);
                }
            }
            case "preview", "rewards" -> {
                Player player = requirePlayer(sender, bundle);
                if (player != null && require(sender, bundle, P_PREVIEW)) {
                    plugin.menus().openPreview(player, 0, false);
                }
            }
            case "admin" -> {
                Player player = requirePlayer(sender, bundle);
                if (player != null && require(sender, bundle, P_ADMIN_MENU)) {
                    plugin.menus().openAdmin(player, 0);
                }
            }
            case "reward" -> reward(sender, bundle, args);
            case "reset" -> reset(sender, bundle, args);
            case "give" -> give(sender, bundle, args);
            case "reload" -> {
                if (require(sender, bundle, P_RELOAD)) {
                    plugin.reload(sender);
                }
            }
            case "reconcile" -> reconcile(sender, bundle, args);
            default -> bundle.messages().send(sender, "unknown-command", Placeholder.unparsed("label", label));
        }
        return true;
    }

    private void openMain(CommandSender sender, ConfigBundle bundle) {
        Player player = requirePlayer(sender, bundle);
        if (player != null && require(sender, bundle, P_USE)) {
            plugin.menus().openMain(player);
        }
    }

    // ------------------------------------------------------------------ /ds reward ...

    private void reward(CommandSender sender, ConfigBundle bundle, String[] args) {
        if (!require(sender, bundle, P_REWARDS)) {
            return;
        }
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        RewardEditor editor = plugin.rewardEditor();
        switch (action) {
            case "addhand" -> {
                Player player = requirePlayer(sender, bundle);
                if (player == null) {
                    return;
                }
                if (args.length != 4) {
                    usage(sender, bundle, "/ds reward addhand <id> <weight>");
                    return;
                }
                editor.addFromHand(player, args[2].toLowerCase(Locale.ROOT), RewardEditor.parseWeight(args[3]));
            }
            case "additem" -> {
                if (args.length != 6) {
                    usage(sender, bundle, "/ds reward additem <id> <material> <amount> <weight>");
                    return;
                }
                Material material = Material.matchMaterial(args[3]);
                if (material == null || material.isAir() || !material.isItem()) {
                    bundle.messages().send(sender, "admin-invalid-material", Placeholder.unparsed("material", args[3]));
                    return;
                }
                int amount = parseInt(args[4]);
                if (amount == Integer.MIN_VALUE) {
                    bundle.messages().send(sender, "admin-invalid-amount", Placeholder.unparsed("max", "2304"));
                    return;
                }
                editor.addItem(sender, args[2].toLowerCase(Locale.ROOT), material, amount, RewardEditor.parseWeight(args[5]));
            }
            case "addcommand" -> {
                // Executable command rewards need their own explicit permission (not granted by default).
                if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission(P_REWARDS_COMMAND)) {
                    bundle.messages().send(sender, "no-permission");
                    return;
                }
                if (args.length < 5) {
                    usage(sender, bundle, "/ds reward addcommand <id> <weight> <command...>");
                    return;
                }
                String cmd = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                editor.addCommand(sender, args[2].toLowerCase(Locale.ROOT), RewardEditor.parseWeight(args[3]), cmd);
            }
            case "remove" -> {
                if (args.length != 3) {
                    usage(sender, bundle, "/ds reward remove <id>");
                    return;
                }
                editor.remove(sender, args[2].toLowerCase(Locale.ROOT));
            }
            case "setweight" -> {
                if (args.length != 4) {
                    usage(sender, bundle, "/ds reward setweight <id> <weight>");
                    return;
                }
                editor.setWeight(sender, args[2].toLowerCase(Locale.ROOT), RewardEditor.parseWeight(args[3]));
            }
            case "setrarity" -> {
                if (args.length != 4) {
                    usage(sender, bundle, "/ds reward setrarity <id> <rarity>");
                    return;
                }
                editor.setRarity(sender, args[2].toLowerCase(Locale.ROOT), args[3].toLowerCase(Locale.ROOT));
            }
            case "list" -> list(sender, bundle);
            default -> bundle.messages().send(sender, "help-reward");
        }
    }

    private void list(CommandSender sender, ConfigBundle bundle) {
        List<RewardDefinition> rewards = bundle.rewards().rewards();
        bundle.messages().send(sender, "admin-list-header",
                Placeholder.unparsed("count", String.valueOf(rewards.size())),
                Placeholder.unparsed("total_weight", RewardItems.formatWeight(bundle.rewards().totalWeight())));
        for (RewardDefinition reward : rewards) {
            bundle.messages().send(sender, "admin-list-entry", RewardItems.resolver(bundle, reward));
        }
        if (rewards.isEmpty()) {
            bundle.messages().send(sender, "no-rewards");
        }
    }

    // ------------------------------------------------------------------ player admin

    private void reset(CommandSender sender, ConfigBundle bundle, String[] args) {
        if (!require(sender, bundle, P_RESET)) {
            return;
        }
        if (args.length != 2) {
            usage(sender, bundle, "/ds reset <player>");
            return;
        }
        OfflinePlayer target = resolve(sender, bundle, args[1]);
        if (target == null) {
            return;
        }
        UUID id = target.getUniqueId();
        String name = target.getName() == null ? args[1] : target.getName();
        plugin.database().submit(repo -> repo.resetCooldown(id, System.currentTimeMillis()))
                .whenComplete((ok, error) -> plugin.sync(() -> {
                    if (error != null) {
                        bundle.messages().send(sender, "error-generic");
                        return;
                    }
                    bundle.messages().send(sender, "admin-reset", Placeholder.unparsed("player", name));
                    plugin.getLogger().info(sender.getName() + " reset the daily cooldown of " + name + " (" + id + ")");
                }));
    }

    private void give(CommandSender sender, ConfigBundle bundle, String[] args) {
        if (!require(sender, bundle, P_GIVE)) {
            return;
        }
        if (args.length != 3) {
            usage(sender, bundle, "/ds give <player> <amount>");
            return;
        }
        int amount = parseInt(args[2]);
        int max = bundle.settings().maxBonusSpins();
        if (amount < 1 || amount > max) {
            bundle.messages().send(sender, "admin-invalid-amount", Placeholder.unparsed("max", String.valueOf(max)));
            return;
        }
        OfflinePlayer target = resolve(sender, bundle, args[1]);
        if (target == null) {
            return;
        }
        UUID id = target.getUniqueId();
        String name = target.getName() == null ? args[1] : target.getName();
        plugin.database().submit(repo -> repo.addBonusSpins(id, amount, max, System.currentTimeMillis()))
                .whenComplete((balance, error) -> plugin.sync(() -> {
                    if (error != null) {
                        bundle.messages().send(sender, "error-generic");
                        return;
                    }
                    if (balance < 0) {
                        bundle.messages().send(sender, "admin-give-cap", Placeholder.unparsed("max", String.valueOf(max)));
                        return;
                    }
                    TagResolver r = TagResolver.resolver(Placeholder.unparsed("player", name),
                            Placeholder.unparsed("amount", String.valueOf(amount)),
                            Placeholder.unparsed("bonus", String.valueOf(balance)));
                    bundle.messages().send(sender, "admin-give", r);
                    Player online = Bukkit.getPlayer(id);
                    if (online != null && online != sender) {
                        bundle.messages().send(online, "bonus-received", r);
                    }
                    plugin.getLogger().info(sender.getName() + " gave " + amount + " bonus spin(s) to " + name + " (" + id + ")");
                }));
    }

    // ------------------------------------------------------------------ reconciliation

    private void reconcile(CommandSender sender, ConfigBundle bundle, String[] args) {
        if (!require(sender, bundle, P_RECONCILE)) {
            return;
        }
        if (args.length <= 1 || args[1].equalsIgnoreCase("list")) {
            plugin.database().submit(repo -> repo.listReconcile(0)).whenComplete((entries, error) -> plugin.sync(() -> {
                if (error != null) {
                    bundle.messages().send(sender, "error-generic");
                    return;
                }
                bundle.messages().send(sender, "reconcile-header", Placeholder.unparsed("count", String.valueOf(entries.size())));
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                for (ReconcileEntry entry : entries.subList(0, Math.min(20, entries.size()))) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(entry.player());
                    bundle.messages().send(sender, "reconcile-entry",
                            Placeholder.unparsed("key", entry.key()),
                            Placeholder.unparsed("player", op.getName() == null ? entry.player().toString() : op.getName()),
                            Placeholder.unparsed("reward", String.valueOf(entry.rewardId())),
                            Placeholder.unparsed("kind", entry.kind()),
                            Placeholder.unparsed("status", entry.status()),
                            Placeholder.unparsed("time", format.format(new Date(entry.updatedAt()))),
                            Placeholder.unparsed("detail", String.valueOf(entry.detail())));
                }
            }));
            return;
        }
        if (args.length != 3 || !(args[2].equalsIgnoreCase("regrant") || args[2].equalsIgnoreCase("dismiss"))) {
            usage(sender, bundle, "/ds reconcile [list | <key> <regrant|dismiss>]");
            return;
        }
        String key = args[1];
        boolean regrant = args[2].equalsIgnoreCase("regrant");
        String actor = sender.getName();
        plugin.database().submit(repo -> repo.resolve(key, regrant, System.currentTimeMillis(), actor))
                .whenComplete((resolution, error) -> plugin.sync(() -> {
                    if (error != null) {
                        bundle.messages().send(sender, "error-generic");
                        return;
                    }
                    if (!resolution.found()) {
                        bundle.messages().send(sender, "reconcile-not-found", Placeholder.unparsed("key", key));
                        return;
                    }
                    plugin.getLogger().warning("[Reconcile] " + actor + (regrant ? " regranted " : " dismissed ") + key);
                    if (!regrant) {
                        bundle.messages().send(sender, "reconcile-dismissed", Placeholder.unparsed("key", key));
                        return;
                    }
                    regrantCommands(sender, bundle, resolution);
                    bundle.messages().send(sender, "reconcile-regranted", Placeholder.unparsed("key", key));
                }));
    }

    /** An explicit administrator decision to re-run a command reward; never done automatically. */
    private void regrantCommands(CommandSender sender, ConfigBundle bundle, SpinRepository.Resolution resolution) {
        RewardSnapshot snapshot = resolution.snapshot();
        if (snapshot == null || snapshot.type() != RewardType.COMMAND) {
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(resolution.player());
        String name = target.getName();
        if (!CommandTemplate.isSafePlayerName(name)) {
            bundle.messages().send(sender, "reconcile-manual", Placeholder.unparsed("commands", String.join(" | ", snapshot.commands())));
            return;
        }
        for (String raw : snapshot.commands()) {
            String cmd = CommandTemplate.apply(raw, name, resolution.player());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                plugin.getLogger().warning("[Reconcile] Re-ran reward command by admin decision: " + cmd);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("[Reconcile] Command failed: " + cmd + " (" + e.getMessage() + ")");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private OfflinePlayer resolve(CommandSender sender, ConfigBundle bundle, String name) {
        if (!name.matches("[A-Za-z0-9_.*\\-]{1,32}")) {
            bundle.messages().send(sender, "player-unknown", Placeholder.unparsed("player", name));
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        // Cached lookup only: never blocks the server thread on a web request.
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null) {
            bundle.messages().send(sender, "player-unknown", Placeholder.unparsed("player", name));
        }
        return cached;
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private boolean require(CommandSender sender, ConfigBundle bundle, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        bundle.messages().send(sender, "no-permission");
        return false;
    }

    private Player requirePlayer(CommandSender sender, ConfigBundle bundle) {
        if (sender instanceof Player player) {
            return player;
        }
        bundle.messages().send(sender, "players-only");
        return null;
    }

    private void usage(CommandSender sender, ConfigBundle bundle, String usage) {
        bundle.messages().send(sender, "usage", Placeholder.unparsed("usage", usage));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        ConfigBundle bundle = plugin.bundle();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("help");
            if (sender.hasPermission(P_CLAIM)) out.add("claim");
            if (sender.hasPermission(P_PREVIEW)) out.add("preview");
            if (sender.hasPermission(P_ADMIN_MENU)) out.add("admin");
            if (sender.hasPermission(P_REWARDS)) out.add("reward");
            if (sender.hasPermission(P_RESET)) out.add("reset");
            if (sender.hasPermission(P_GIVE)) out.add("give");
            if (sender.hasPermission(P_RELOAD)) out.add("reload");
            if (sender.hasPermission(P_RECONCILE)) out.add("reconcile");
            return filter(out, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reward") && sender.hasPermission(P_REWARDS)) {
            if (args.length == 2) {
                out.addAll(List.of("addhand", "additem", "remove", "list", "setweight", "setrarity"));
                if (sender instanceof ConsoleCommandSender || sender.hasPermission(P_REWARDS_COMMAND)) {
                    out.add("addcommand");
                }
                return filter(out, args[1]);
            }
            String action = args[1].toLowerCase(Locale.ROOT);
            List<String> ids = bundle == null ? List.of() : bundle.rewards().rewards().stream().map(RewardDefinition::id).toList();
            switch (action) {
                case "remove", "setweight", "setrarity" -> {
                    if (args.length == 3) return filter(ids, args[2]);
                    if (args.length == 4 && action.equals("setweight")) return List.of("1", "5", "10", "25");
                    if (args.length == 4 && action.equals("setrarity") && bundle != null) {
                        return filter(new ArrayList<>(bundle.rewards().rarities().keySet()), args[3]);
                    }
                }
                case "addhand" -> {
                    if (args.length == 3) return List.of("<id>");
                    if (args.length == 4) return List.of("10");
                }
                case "additem" -> {
                    if (args.length == 3) return List.of("<id>");
                    if (args.length == 4) {
                        String prefix = args[3].toUpperCase(Locale.ROOT);
                        return Arrays.stream(Material.values())
                                .filter(m -> m.isItem() && !m.isAir() && !m.isLegacy() && m.name().startsWith(prefix))
                                .limit(50).map(m -> m.name().toLowerCase(Locale.ROOT)).collect(Collectors.toList());
                    }
                    if (args.length == 5) return List.of("1", "16", "64");
                    if (args.length == 6) return List.of("10");
                }
                case "addcommand" -> {
                    if (args.length == 3) return List.of("<id>");
                    if (args.length == 4) return List.of("10");
                    if (args.length == 5) return List.of("<command with {player}>");
                }
                default -> {
                }
            }
            return List.of();
        }
        if ((sub.equals("reset") && sender.hasPermission(P_RESET)) || (sub.equals("give") && sender.hasPermission(P_GIVE))) {
            if (args.length == 2) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    out.add(p.getName());
                }
                return filter(out, args[1]);
            }
            if (args.length == 3 && sub.equals("give")) {
                return List.of("1", "3", "5");
            }
        }
        if (sub.equals("reconcile") && sender.hasPermission(P_RECONCILE)) {
            if (args.length == 2) return filter(List.of("list"), args[1]);
            if (args.length == 3) return filter(List.of("regrant", "dismiss"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}
