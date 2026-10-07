package dev.exoquests.paper.command;

import static dev.exoquests.paper.text.TextService.n;
import static dev.exoquests.paper.text.TextService.p;

import dev.exoquests.core.config.ConfigException;
import dev.exoquests.core.config.ShopLoader;
import dev.exoquests.core.points.PointsService;
import dev.exoquests.core.shop.CommandTemplate;
import dev.exoquests.core.shop.ItemSpec;
import dev.exoquests.core.shop.PriceRules;
import dev.exoquests.core.shop.PurchaseService;
import dev.exoquests.core.shop.ShopEntry;
import dev.exoquests.core.storage.PointsStore;
import dev.exoquests.core.storage.PurchaseRecord;
import dev.exoquests.paper.ConfigManager;
import dev.exoquests.paper.ExoQuestsPlugin;
import dev.exoquests.paper.shop.ShopEditor;
import dev.exoquests.paper.text.TextService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /exoquests} administration. Every subcommand checks its own permission, validates its input,
 * resolves players by UUID (online, previously joined, or a literal UUID) and writes an audit record.
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("help", "reload", "points", "reset", "shop", "recovery");

    private final ExoQuestsPlugin plugin;

    public AdminCommand(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
    }

    private TextService text() {
        return plugin.text();
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getName() + " (" + player.getUniqueId() + ")" : "console";
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        text().send(sender, "error.no-permission");
        return false;
    }

    private void usage(CommandSender sender, String usage) {
        text().send(sender, "error.usage", p("usage", usage));
    }

    private void audit(CommandSender sender, String action, String target, String details) {
        String actor = actor(sender);
        plugin.getLogger().info("[audit] " + actor + " " + action + " " + target + (details == null ? "" : " " + details));
        plugin.points().audit(actor, action, target, details).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "could not write audit record", error);
            return null;
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            if (sender.hasPermission("exoquests.admin") || hasAnyAdmin(sender)) {
                text().sendList(sender, "admin-help");
            } else {
                text().send(sender, "error.no-permission");
            }
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (allowed(sender, "exoquests.admin.reload")) {
                    reload(sender);
                }
            }
            case "points" -> {
                if (allowed(sender, "exoquests.admin.points")) {
                    points(sender, rest);
                }
            }
            case "reset" -> {
                if (allowed(sender, "exoquests.admin.reset")) {
                    reset(sender, rest);
                }
            }
            case "shop" -> {
                if (allowed(sender, "exoquests.admin.shop")) {
                    shop(sender, rest);
                }
            }
            case "recovery" -> {
                if (allowed(sender, "exoquests.admin.recovery")) {
                    recovery(sender, rest);
                }
            }
            default -> usage(sender, "/exoquests help");
        }
        return true;
    }

    private static boolean hasAnyAdmin(CommandSender sender) {
        for (String s : List.of("reload", "points", "reset", "shop", "recovery")) {
            if (sender.hasPermission("exoquests.admin." + s)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ reload

    private void reload(CommandSender sender) {
        plugin.configs().reload().whenCompleteAsync((bundle, error) -> {
            if (error == null) {
                text().send(sender, "admin.reloaded");
                audit(sender, "config.reload", "-", "ok");
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (cause instanceof ConfigException config) {
                ConfigManager.logProblems(plugin.getLogger(), config);
                text().send(sender, "admin.reload-failed", n("count", config.problems().size()));
                audit(sender, "config.reload", "-", "rejected: " + config.problems().size() + " problem(s)");
            } else {
                plugin.getLogger().log(Level.SEVERE, "reload failed", cause);
                text().send(sender, "admin.reload-failed", n("count", 1));
            }
        }, plugin.mainExecutor());
    }

    // ------------------------------------------------------------------ points

    private void points(CommandSender sender, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("view")) {
            Optional<PlayerResolver.Target> target = resolve(sender, args[1]);
            target.ifPresent(t -> plugin.points().balance(t.uuid()).whenCompleteAsync((balance, error) -> {
                if (error != null) {
                    text().send(sender, "error.database");
                } else {
                    text().send(sender, "points.balance-other", p("player", t.name()), n("points", balance));
                }
            }, plugin.mainExecutor()));
            return;
        }
        if (args.length != 3) {
            usage(sender, "/exoquests points <give|take|set> <player> <amount>");
            return;
        }
        PointsService.Op op;
        try {
            op = PointsService.Op.valueOf(args[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            usage(sender, "/exoquests points <give|take|set> <player> <amount>");
            return;
        }
        long max = plugin.configs().current().settings().maxBalance();
        if (!args[2].matches("[0-9]{1,19}")) {
            text().send(sender, "error.invalid-number", p("input", args[2]));
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            text().send(sender, "admin.points-invalid-amount", n("max", max));
            return;
        }
        if (!plugin.points().validAmount(op, amount)) {
            text().send(sender, "admin.points-invalid-amount", n("max", max));
            return;
        }
        Optional<PlayerResolver.Target> resolved = resolve(sender, args[1]);
        if (resolved.isEmpty()) {
            return;
        }
        PlayerResolver.Target target = resolved.get();
        plugin.points().adjust(op, target.uuid(), amount, actor(sender)).whenCompleteAsync((change, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "points adjustment failed", error);
                text().send(sender, "error.database");
                return;
            }
            if (change.applied()) {
                String delta = (change.delta() >= 0 ? "+" : "") + String.format(Locale.ROOT, "%,d", change.delta());
                text().send(sender, "admin.points-updated", p("player", target.name()), p("delta", delta),
                        n("balance", change.balance()));
                plugin.getLogger().info("[audit] " + actor(sender) + " points." + op.name().toLowerCase(Locale.ROOT)
                        + " " + target.uuid() + " amount=" + amount + " balance=" + change.balance());
                Player online = Bukkit.getPlayer(target.uuid());
                if (online != null) {
                    plugin.menus().refresh(online);
                }
            } else if (change.failure() == PointsStore.Failure.INSUFFICIENT_FUNDS) {
                text().send(sender, "admin.points-insufficient", p("player", target.name()), n("balance", change.balance()));
            } else {
                text().send(sender, "admin.points-at-max", p("player", target.name()));
            }
        }, plugin.mainExecutor());
    }

    private Optional<PlayerResolver.Target> resolve(CommandSender sender, String input) {
        Optional<PlayerResolver.Target> target = PlayerResolver.resolve(input);
        if (target.isEmpty()) {
            text().send(sender, "error.unknown-player", p("input", input));
        }
        return target;
    }

    // ------------------------------------------------------------------ reset

    private void reset(CommandSender sender, String[] args) {
        if (args.length != 1) {
            usage(sender, "/exoquests reset <player>");
            return;
        }
        resolve(sender, args[0]).ifPresent(target -> plugin.progress().adminReset(target.uuid())
                .whenCompleteAsync((rows, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "quest reset failed", error);
                        text().send(sender, "error.database");
                        return;
                    }
                    text().send(sender, "admin.reset-done", p("player", target.name()));
                    audit(sender, "quests.reset", target.uuid().toString(),
                            "period=" + plugin.progress().currentPeriod() + " rows=" + rows);
                }, plugin.mainExecutor()));
    }

    // ------------------------------------------------------------------ shop

    private void shop(CommandSender sender, String[] args) {
        if (args.length == 0) {
            usage(sender, "/exoquests shop <additem|addhand|addcommand|remove|setprice|list>");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> listShop(sender);
            case "additem" -> addItem(sender, args);
            case "addhand" -> addHand(sender, args);
            case "addcommand" -> addCommand(sender, args);
            case "remove" -> {
                if (args.length != 2) {
                    usage(sender, "/exoquests shop remove <id>");
                    return;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                finishEdit(sender, plugin.shopEditor().remove(id), "shop.remove", id, null, 0);
            }
            case "setprice" -> {
                if (args.length != 3) {
                    usage(sender, "/exoquests shop setprice <id> <price>");
                    return;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                OptionalInt price = PriceRules.parse(args[2]);
                if (price.isEmpty()) {
                    text().send(sender, "admin.shop-invalid-price");
                    return;
                }
                finishEdit(sender, plugin.shopEditor().setPrice(id, price.getAsInt()), "shop.setprice", id,
                        "price=" + price.getAsInt(), price.getAsInt());
            }
            default -> usage(sender, "/exoquests shop <additem|addhand|addcommand|remove|setprice|list>");
        }
    }

    /** Validates the id and price shared by the add commands. Returns the price or -1. */
    private int idAndPrice(CommandSender sender, String id, String price) {
        if (!ShopLoader.ID.matcher(id).matches()) {
            text().send(sender, "admin.shop-invalid-id");
            return -1;
        }
        OptionalInt parsed = PriceRules.parse(price);
        if (parsed.isEmpty()) {
            text().send(sender, "admin.shop-invalid-price");
            return -1;
        }
        return parsed.getAsInt();
    }

    private void addItem(CommandSender sender, String[] args) {
        if (args.length != 4 && args.length != 5) {
            usage(sender, "/exoquests shop additem <id> <price> <material> [amount]");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        int price = idAndPrice(sender, id, args[2]);
        if (price < 0) {
            return;
        }
        Material material = Material.matchMaterial(args[3]);
        if (material == null || !material.isItem() || material.isAir()) {
            text().send(sender, "admin.shop-invalid-material", p("input", args[3]));
            return;
        }
        int amount = 1;
        if (args.length == 5) {
            if (!args[4].matches("[0-9]{1,5}") || Integer.parseInt(args[4]) < 1
                    || Integer.parseInt(args[4]) > ItemSpec.MAX_AMOUNT) {
                text().send(sender, "admin.shop-invalid-amount", n("max", ItemSpec.MAX_AMOUNT));
                return;
            }
            amount = Integer.parseInt(args[4]);
        }
        ShopEntry entry = ShopEditor.itemEntry(id, price, ItemSpec.simple(material.name(), amount));
        finishEdit(sender, plugin.shopEditor().add(entry), "shop.additem", id,
                "price=" + price + " material=" + material.name() + " amount=" + amount, price);
    }

    private void addHand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            text().send(sender, "error.player-only");
            return;
        }
        if (args.length != 3) {
            usage(sender, "/exoquests shop addhand <id> <price>");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        int price = idAndPrice(sender, id, args[2]);
        if (price < 0) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            text().send(sender, "admin.shop-empty-hand");
            return;
        }
        // Serialize a copy: the held item and its stack size are left untouched.
        ItemStack copy = held.clone();
        String data = Base64.getEncoder().encodeToString(copy.serializeAsBytes());
        ShopEntry entry = ShopEditor.itemEntry(id, price, ItemSpec.serialized(data));
        finishEdit(sender, plugin.shopEditor().add(entry), "shop.addhand", id,
                "price=" + price + " item=" + copy.getType().name() + " x" + copy.getAmount(), price);
    }

    private void addCommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            usage(sender, "/exoquests shop addcommand <id> <price> <command...>");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        int price = idAndPrice(sender, id, args[2]);
        if (price < 0) {
            return;
        }
        String commandLine = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        String problem = CommandTemplate.validate(commandLine);
        if (problem != null) {
            text().send(sender, "admin.shop-invalid-command", p("reason", problem));
            return;
        }
        ShopEntry entry = ShopEditor.commandEntry(id, price, CommandTemplate.normalize(commandLine));
        finishEdit(sender, plugin.shopEditor().add(entry), "shop.addcommand", id,
                "price=" + price + " command=" + CommandTemplate.normalize(commandLine), price);
    }

    private void finishEdit(CommandSender sender, CompletableFuture<ShopEditor.Outcome> edit, String action, String id,
                            String details, int price) {
        edit.whenCompleteAsync((outcome, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                if (cause instanceof ConfigException config) {
                    ConfigManager.logProblems(plugin.getLogger(), config);
                } else {
                    plugin.getLogger().log(Level.SEVERE, "shop edit failed", cause);
                }
                text().send(sender, "admin.shop-save-failed");
                return;
            }
            switch (outcome) {
                case EXISTS -> text().send(sender, "admin.shop-exists", p("id", id));
                case UNKNOWN -> text().send(sender, "admin.shop-unknown", p("id", id));
                case OK -> {
                    audit(sender, action, id, details);
                    switch (action) {
                        case "shop.remove" -> text().send(sender, "admin.shop-removed", p("id", id));
                        case "shop.setprice" -> text().send(sender, "admin.shop-price-set", p("id", id), n("price", price));
                        default -> text().send(sender, "admin.shop-added", p("id", id), n("price", price));
                    }
                }
            }
        }, plugin.mainExecutor());
    }

    private void listShop(CommandSender sender) {
        List<ShopEntry> entries = plugin.configs().current().shop().all();
        text().send(sender, "admin.shop-list-header", n("count", entries.size()));
        for (ShopEntry e : entries) {
            text().send(sender, "admin.shop-list-entry", p("id", e.id()), n("price", e.price()),
                    p("type", e.type().name().toLowerCase(Locale.ROOT)), p("state", e.enabled() ? "enabled" : "disabled"));
        }
    }

    // ------------------------------------------------------------------ recovery

    private void recovery(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            plugin.purchases().listReview(20).whenCompleteAsync((records, error) -> {
                if (error != null) {
                    text().send(sender, "error.database");
                    return;
                }
                if (records.isEmpty()) {
                    text().send(sender, "admin.recovery-none");
                    return;
                }
                text().send(sender, "admin.recovery-header", n("count", records.size()));
                for (PurchaseRecord r : records) {
                    String name = Optional.ofNullable(Bukkit.getOfflinePlayer(r.player()).getName())
                            .orElse(r.player().toString());
                    text().send(sender, "admin.recovery-entry", p("id", r.purchaseId()), p("player", name),
                            p("item", r.itemId()), n("price", r.price()), p("note", r.note() == null ? "-" : r.note()));
                }
            }, plugin.mainExecutor());
            return;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("resolve")) {
            PurchaseService.Resolution resolution;
            try {
                resolution = PurchaseService.Resolution.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                usage(sender, "/exoquests recovery resolve <purchase-id> <delivered|refund>");
                return;
            }
            String id = args[1];
            plugin.purchases().resolve(id, resolution, actor(sender)).whenCompleteAsync((done, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "recovery resolve failed", error);
                    text().send(sender, "error.database");
                } else if (done) {
                    text().send(sender, "admin.recovery-resolved", p("id", id),
                            p("resolution", resolution.name().toLowerCase(Locale.ROOT)));
                    plugin.getLogger().info("[audit] " + actor(sender) + " purchase.resolve " + id + " " + resolution);
                } else {
                    text().send(sender, "admin.recovery-not-found", p("id", id));
                }
            }, plugin.mainExecutor());
            return;
        }
        usage(sender, "/exoquests recovery <list|resolve <purchase-id> <delivered|refund>>");
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> visible = new ArrayList<>();
            for (String s : SUBCOMMANDS) {
                if (s.equals("help") || sender.hasPermission("exoquests.admin." + s)) {
                    visible.add(s);
                }
            }
            return Completions.filter(visible, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("help") && !sender.hasPermission("exoquests.admin." + sub)) {
            return List.of();
        }
        switch (sub) {
            case "points" -> {
                if (args.length == 2) {
                    return Completions.filter(List.of("give", "take", "set", "view"), args[1]);
                }
                if (args.length == 3) {
                    return Completions.players(args[2]);
                }
                if (args.length == 4 && !args[1].equalsIgnoreCase("view")) {
                    return Completions.filter(List.of("10", "50", "100"), args[3]);
                }
            }
            case "reset" -> {
                if (args.length == 2) {
                    return Completions.players(args[1]);
                }
            }
            case "shop" -> {
                return shopCompletions(args);
            }
            case "recovery" -> {
                if (args.length == 2) {
                    return Completions.filter(List.of("list", "resolve"), args[1]);
                }
                if (args.length == 4 && args[1].equalsIgnoreCase("resolve")) {
                    return Completions.filter(List.of("delivered", "refund"), args[3]);
                }
            }
            default -> {
                return List.of();
            }
        }
        return List.of();
    }

    private List<String> shopCompletions(String[] args) {
        if (args.length == 2) {
            return Completions.filter(List.of("additem", "addhand", "addcommand", "remove", "setprice", "list"), args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        List<String> ids = plugin.configs().current().shop().all().stream().map(ShopEntry::id).toList();
        if (args.length == 3 && (sub.equals("remove") || sub.equals("setprice"))) {
            return Completions.filter(ids, args[2]);
        }
        if (args.length == 4 && (sub.startsWith("add") || sub.equals("setprice"))) {
            return Completions.filter(List.of("10", "25", "60", "120", "300", "1000"), args[3]);
        }
        if (args.length == 5 && sub.equals("additem")) {
            List<String> materials = new ArrayList<>();
            String prefix = args[4].toUpperCase(Locale.ROOT);
            for (Material m : Material.values()) {
                if (m.isItem() && !m.isAir() && m.name().startsWith(prefix)) {
                    materials.add(m.name());
                    if (materials.size() >= 50) {
                        break;
                    }
                }
            }
            return materials;
        }
        if (args.length == 6 && sub.equals("additem")) {
            return Completions.filter(List.of("1", "16", "32", "64"), args[5]);
        }
        if (args.length >= 5 && sub.equals("addcommand")) {
            return Completions.filter(List.of("{player}", "{uuid}", "{item_id}", "{purchase_id}", "{price}"),
                    args[args.length - 1]);
        }
        return List.of();
    }
}
